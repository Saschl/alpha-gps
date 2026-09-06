package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryCameraName

import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.IosAppPreferences
import com.sasch.cameragps.sharednew.IosLaunchContext
import com.sasch.cameragps.sharednew.IosTransmissionNotifications
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.centralShell
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.clearPairingFailedDevice
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.ensureInitialized
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.reconnectToPersistedPeripherals
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.startCentralIfNeeded
import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
import com.sasch.cameragps.sharednew.bluetooth.session.OrchestratorEvent
import com.sasch.cameragps.sharednew.crash.IosCrashReporting
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import com.sasch.cameragps.sharednew.logging.IosLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralStateConnected
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * iOS Bluetooth policy layer and the stable facade the shared Compose UI
 * consumes. The pieces underneath:
 * - [IosCentralShell] — CBCentralManager, delegate, state restoration, scan and
 *   connect/disconnect mechanics
 * - [IosDeviceRepository] — device database, legacy auto-reconnect store
 *   (migration only), enabled-state caches
 * - the shared [CameraSessionOrchestrator] — sequential BLE queue, handshake,
 *   pairing retries, location transmission
 *
 * This object keeps the decisions: auto-reconnect policy, app/device-enabled
 * lifecycle sweeps, pairing-failure UI state and device-list assembly.
 *
 * [IosAccessoryCoordinator] owns accessory setup and migration policy. This
 * controller retains central ownership and forwards the public migration API.
 *
 * Repository, transport and the inert accessory collaborators exist before
 * ensureInitialized creates the central. Restoration callbacks may run inside
 * that constructor: use their shell parameter, never the unassigned shell field.
 * No callback may force lazy central creation or delay launch on authorization.
 *
 * Call [ensureInitialized] from AppDelegate as early as possible.
 */
@OptIn(ExperimentalForeignApi::class)
object IosBluetoothController : BluetoothController {

    /**
     * Touch this property from the Swift `AppDelegate.didFinishLaunchingWithOptions`
     * to guarantee the CBCentralManager is alive before the restoration timeout.
     *
     * Usage from Swift:
     * ```swift
     * IosBluetoothController.shared.ensureInitialized()
     * ```
     */
    fun ensureInitialized() {
        // Logging first: everything below is worth a log line, and on a
        // background launch the Compose UI never starts, so this is the only
        // place logging gets configured at all.
        // An unparseable stored level used to throw straight out of
        // didFinishLaunchingWithOptions, which is a crash loop on every
        // background relaunch with no UI to notice it.
        val level = runCatching { LogLevel.valueOf(IosAppPreferences.getLogLevel()) }
            .getOrDefault(LogLevel.Info)
        IosLogging.install(LogRepository(getDatabaseBuilder()), level)
        // After the loggers exist, so an init failure is logged, and before
        // anything below can throw: crash reporting is most useful exactly on
        // the background-relaunch paths that have no UI to notice a problem.
        IosCrashReporting.startIfConsented()
        logging.i {
            "Launch (${IosLaunchContext.describe()}): appEnabled=$appEnabled " +
                    "migrationDone=${IosAppPreferences.isAccessoryMigrationDone()}"
        }

        // The central comes up on every launch, unconditionally. State
        // restoration only delivers willRestoreState if the manager is recreated
        // inside didFinishLaunchingWithOptions, so anything conditional or
        // asynchronous here kills background reconnect.
        //
        // AccessorySetupKit refuses to migrate while a central exists, so the
        // migration flow tears it down only after the user confirms in the app.
        accessorySession.activate()
        startCentralIfNeeded()
        transmissionNotifications.start()
        controllerScope.launch { accessories.evaluateMigration() }
    }

    private val logging = logging()

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val deviceDao: CameraDeviceDAO by lazy {
        LogDatabase.getRoomDatabase(getDatabaseBuilder()).cameraDeviceDao()
    }

    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices

    /** Per-device session state for the UI — read [CameraSession] fields directly. */
    val sessions: StateFlow<Map<String, CameraSession>> get() = orchestrator.sessions

    /** Global transmission gate (location updates running). */
    val transmissionActive: StateFlow<Boolean> get() = orchestrator.locationManager.isActive

    /**
     * Display name of the last device whose pairing was rejected by the camera
     * (pairing gate or auth retries exhausted). The UI shows a troubleshooting
     * dialog while this is non-null and clears it via [clearPairingFailedDevice].
     * A later successful handshake with the same device also clears it.
     */
    private val _pairingFailedDevice = MutableStateFlow<String?>(null)
    val pairingFailedDevice: StateFlow<String?> = _pairingFailedDevice

    private var pairingFailedIdentifier: String? = null

    fun clearPairingFailedDevice() {
        pairingFailedIdentifier = null
        _pairingFailedDevice.value = null
    }

    private fun reportPairingFailure(identifier: String, displayName: String?) {
        pairingFailedIdentifier = identifier
        _pairingFailedDevice.value = displayName
            ?: repository.deviceNameFor(identifier)
                    ?: identifier
    }

    /**
     * True when location authorization is stuck below Always and only the Settings
     * app can raise it: iOS shows the WhenInUse→Always upgrade prompt at most once,
     * and a denied/restricted status never prompts again. Seeded in init and kept
     * current from CLLocationManager's authorization-change callback.
     */
    private val _needsAlwaysLocationAuthorization = MutableStateFlow(false)
    val needsAlwaysLocationAuthorization: StateFlow<Boolean> = _needsAlwaysLocationAuthorization

    override val capabilities: Set<BluetoothCapability> = setOf(
        BluetoothCapability.Scan,
        BluetoothCapability.Connect,
        BluetoothCapability.ObserveConnection,
    )

    private var appEnabled = IosAppPreferences.isAppEnabled()

    /**
     * Whether the current central is powered on. Exposed for platform UI state;
     * accessory discovery itself is owned by AccessorySetupKit.
     */
    private val _bluetoothPoweredOn = MutableStateFlow(false)
    val bluetoothPoweredOn: StateFlow<Boolean> = _bluetoothPoweredOn

    // --- Collaborators (constructed before the central is started at launch) ---

    private val repository = IosDeviceRepository(
        deviceDao = { deviceDao },
        resolveNames = { ids -> shell?.retrieveNames(ids) ?: emptyMap() },
    )

    private val transport: IosBleTransport = IosBleTransport(
        scope = controllerScope,
        onPairingGateExhausted = { peripheral ->
            reportPairingFailure(
                identifier = peripheral.identifier.UUIDString,
                displayName = peripheral.name,
            )
            shell?.cancelConnection(peripheral)
        },
    )

    /**
     * AccessorySetupKit. Constructed here but INERT until [ensureInitialized]
     * activates it, so it cannot call back before the rest of the graph exists.
     */
    private val accessorySession = IosAccessoryShell(
        onAccessoriesChanged = { persistAccessoryNames() },
        onAccessoryAdded = { id, name -> handleAccessoryAdded(id, name) },
        onAccessoryRemoved = { id -> handleAccessoryRemoved(id) },
        onMigrationComplete = { accessories.handleMigrationComplete() },
    )

    private val accessories: IosAccessoryCoordinator = IosAccessoryCoordinator(
        controllerScope = controllerScope,
        accessorySession = accessorySession,
        store = IosAccessoryMigrationStore(repository),
        connections = object : IosAccessoryCoordinator.Connections {
            override fun releaseCentral(): Boolean {
                val hadCentral = centralShell != null
                stopCentral()
                return hadCentral
            }

            override fun resumeConnections(reconnectExisting: Boolean) {
                val alreadyRunning = centralShell != null
                startCentralIfNeeded()
                if (alreadyRunning && reconnectExisting) {
                    controllerScope.launch { reconnectToPersistedPeripherals() }
                }
            }
        },
        onDevicesChanged = { refreshDeviceListFrom(shell) },
    )

    val migrationCandidates: StateFlow<List<PendingMigration>> get() = accessories.migrationCandidates
    val migrationNeedsRestart: StateFlow<Boolean> get() = accessories.migrationNeedsRestart
    val migrationError: StateFlow<Boolean> get() = accessories.migrationError
    val migrationInProgress: StateFlow<Boolean> get() = accessories.migrationInProgress

    fun clearMigrationError() = accessories.clearMigrationError()
    fun consumeAutoMigrationPrompt(): Boolean = accessories.consumeAutoMigrationPrompt()
    suspend fun presentMigrationPicker(): Boolean = accessories.presentMigrationPicker()
    suspend fun presentAccessoryPicker(): Boolean = accessories.presentAccessoryPicker()
    fun resetAccessoryMigrationForTesting() = accessories.resetAccessoryMigrationForTesting()

    /**
     * The CBCentralManager, created on demand by [startCentralIfNeeded].
     *
     * Created synchronously by ensureInitialized for normal/background launches,
     * released only during foreground migration. Existing unmigrated cameras can
     * still connect. Construction may synchronously fire `willRestoreState`.
     */
    private var centralShell: IosCentralShell? = null

    /**
     * Scope for the central's own delegate work. Its coroutines capture the
     * CBCentralManager, so cancelling this is what actually lets the manager be
     * released; dropping [centralShell] alone would leave in-flight reconnect
     * coroutines holding it alive.
     */
    private var centralScope: CoroutineScope? = null

    private fun createCentralShell(scope: CoroutineScope) = IosCentralShell(
        scope = scope,
        transport = transport,
        isAppEnabled = { appEnabled },
        shouldAutoReconnect = { id -> shouldAutoReconnect(id) },
        onPoweredOn = { restored -> handlePoweredOn(restored) },
        onCentralStateChanged = { poweredOn -> _bluetoothPoweredOn.value = poweredOn },
        onPeripheralConnected = { id -> handlePeripheralConnected(id) },
        // Fires during shell construction (restoration) — must use the parameter,
        // never this object's `shell` field
        onKnownPeripheralsChanged = { s -> refreshDeviceListFrom(s) },
    )

    /**
     * The central, or null while it has not been created yet. Reading this never
     * creates it — only [startCentralIfNeeded] does, so no incidental call can
     * bring the central up underneath the migration flow.
     */
    private val shell: IosCentralShell? get() = centralShell

    /**
     * Resolve [identifier] against the central's known peripherals, falling back
     * to the normalized form while the central does not exist yet.
     */
    private fun resolved(identifier: String): String =
        shell?.resolveKnownIdentifier(identifier) ?: identifier.uppercase()

    /**
     * Normal launches create the central synchronously for state restoration and
     * continuity of existing connections. Only an explicit migration attempt
     * holds creation off; callbacks must not recreate it underneath the picker.
     * Never gate launch creation on the asynchronously loaded authorized set.
     */
    private fun startCentralIfNeeded() {
        if (centralShell != null || migrationInProgress.value) return
        logging.i { "Creating the CBCentralManager" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        centralScope = scope
        centralShell = createCentralShell(scope)
    }

    @OptIn(NativeRuntimeApi::class)
    private fun stopCentral() {
        if (centralShell == null) return
        logging.i { "Releasing the CBCentralManager" }
        forceShutdownAllConnections()
        // Cancel first: a suspended reconnect coroutine captures the manager and
        // would keep it alive no matter what happens to the reference below.
        centralScope?.cancel()
        centralShell?.stopScan()
        centralScope = null
        centralShell = null
        _bluetoothPoweredOn.value = false
        // Dropping the Kotlin reference does not release the Objective-C object
        // straight away: Kotlin/Native hands that to its garbage collector. The
        // picker checks for a live CBCentralManager, so nudge the collector.
        // The coordinator keeps creation blocked until migration ends.
        GC.collect()
    }

    private val locationSource = IosLocationSource()

    private val orchestrator = CameraSessionOrchestrator(
        transport = transport,
        locationSource = locationSource,
        deviceDao = deviceDao,
        scope = controllerScope,
        shouldRemainConnected = { identifier ->
            if (!repository.isDeviceEnabled(identifier)) {
                disconnect(identifier)
                false
            } else {
                true
            }
        },
        isTransmissionAllowed = { appEnabled },
    )

    private val transmissionNotifications = IosTransmissionNotifications(
        controllerScope, orchestrator.sessions, orchestrator.locationManager.isTransmitting,
    )
    val transmissionNotificationsEnabled: StateFlow<Boolean> get() = transmissionNotifications.enabled
    val transmissionNotificationsPermissionDenied: StateFlow<Boolean> get() = transmissionNotifications.permissionDenied
    fun setTransmissionNotificationsEnabled(enabled: Boolean) =
        transmissionNotifications.setEnabled(enabled)

    fun hasPreciseAccuracyAuthorization(): Boolean = locationSource.hasPreciseAuthorization()

    init {
        locationSource.onAuthorizationChanged = {
            _needsAlwaysLocationAuthorization.value =
                locationSource.needsAlwaysAuthorizationFromSettings()
            orchestrator.locationManager.updateTracking()
        }
        _needsAlwaysLocationAuthorization.value =
            locationSource.needsAlwaysAuthorizationFromSettings()
        orchestrator.start()

        controllerScope.launch {
            repository.sync()
            refreshDeviceListFrom(shell)
        }
        controllerScope.launch {
            orchestrator.events.collect { event ->
                when (event) {
                    is OrchestratorEvent.PairingFailed -> {
                        val knownIdentifier = resolved(event.identifier)
                        val peripheral = shell?.connectedPeripherals?.get(knownIdentifier)
                        reportPairingFailure(
                            identifier = knownIdentifier,
                            displayName = peripheral?.name,
                        )
                        if (peripheral != null) {
                            shell?.cancelConnection(peripheral)
                        }
                    }

                    is OrchestratorEvent.HandshakeCompleted -> {
                        val failed = pairingFailedIdentifier
                        if (failed != null && failed.equals(event.identifier, ignoreCase = true)) {
                            clearPairingFailedDevice()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Shell event handlers (post-construction only — may use `shell`)
    // ---------------------------------------------------------------------------

    private fun handlePoweredOn(restored: List<CBPeripheral>) {
        if (!appEnabled) {
            shell?.stopScanIfNeeded()
            shell?.cancelConnections(restored)
            shell?.cancelAllKnownConnections()
            controllerScope.launch {
                withDatabase("power-on sweep (app disabled)") {
                    repository.loadStoreFromDisk()
                    repository.migrateLegacyDevicesToDatabase()
                    repository.sync()
                }
                refreshDeviceListFrom(shell)
            }
            refreshDeviceListFrom(shell)
            return
        }
        // No scan here on purpose: reconnecting goes through
        // retrievePeripheralsWithIdentifiers plus a pending connect and never
        // needed one, while discovering new cameras belongs to the device-list
        // screen. Starting one here also leaked a permanently running background
        // scan, because on a background launch no UI ever runs to stop it.
        controllerScope.launch {
            withDatabase("power-on sweep") {
                // One query up front so attachRestoredPeripherals reads the
                // in-memory enabled cache instead of hitting the DAO per device
                // on the launch critical path.
                repository.sync()
                attachRestoredPeripherals(restored)
                reconnectToPersistedPeripherals()
            }
        }
    }

    /**
     * On a background restoration launch before the device has been unlocked
     * since boot, file protection can make the Room file unopenable. An uncaught
     * throw there kills the launch silently, so every launch-path database
     * access goes through here.
     */
    private suspend fun withDatabase(what: String, block: suspend () -> Unit): Boolean =
        runCatching { block() }
            .onFailure { logging.e(it, msg = { "Database work failed during $what" }) }
            .isSuccess

    private fun handlePeripheralConnected(identifier: String) {
        repository.markAutoReconnect(identifier)
        refreshDeviceListFrom(shell)
        // The hardware name may only become available after authorization/connection.
        controllerScope.launch {
            withDatabase("camera name after connection") { ensureDeviceRecord(identifier) }
            refreshDeviceListFrom(shell)
        }
    }

    // ---------------------------------------------------------------------------
    // BluetoothController implementation
    // ---------------------------------------------------------------------------

    /**
     * No-op: discovery is the AccessorySetupKit picker's job. With
     * AccessorySetupKit declared, a CoreBluetooth scan only ever returns
     * accessories the user has already authorized, so scanning for new cameras
     * cannot work and starting one would just burn radio.
     */
    override suspend fun startScan() = Unit

    override suspend fun stopScan() {
        shell?.stopScan()
    }

    override suspend fun connect(identifier: String): Boolean {
        val central = shell ?: run {
            logging.e { "Cannot connect: the central has not been created yet" }
            return false
        }
        val resolvedIdentifier = central.resolveKnownIdentifier(identifier)
        val peripheral = central.discoveredPeripheral(resolvedIdentifier) ?: return false
        if (central.isConnected(resolvedIdentifier)) return true
        if (!central.isPoweredOn) {
            logging.e { "Cannot connect: CBCentralManager is not powered on" }
            return false
        }
        ensureDeviceRecord(resolvedIdentifier, peripheral.name)
        return central.awaitConnect(peripheral, resolvedIdentifier)
    }

    override suspend fun disconnect(identifier: String) {
        disconnectInternal(identifier, removeFromAutoReconnect = true)
    }

    private suspend fun disconnectInternal(identifier: String, removeFromAutoReconnect: Boolean) {
        val resolvedIdentifier = resolved(identifier)
        if (removeFromAutoReconnect) {
            repository.removeAutoReconnect(identifier)
        }
        shell?.awaitDisconnect(resolvedIdentifier)
    }

    override suspend fun forgetDevice(identifier: String) {
        val resolvedIdentifier = resolved(identifier)
        disconnect(identifier)
        shell?.forget(resolvedIdentifier)
        // Also drop the AccessorySetupKit authorization, which removes the
        // Bluetooth bond. Without this the camera stays paired at the OS level
        // and users have to finish the job in Settings.
        accessorySession.remove(resolvedIdentifier)
        repository.deleteDevice(resolvedIdentifier)
        refreshDeviceListFrom(shell)
    }

    /** Run a full shutter cycle (half press → focus delay → full press → releases). */
    fun triggerShutterSequence(identifier: String): Boolean {
        val session = orchestrator.registry.get(identifier) ?: return false
        if (session.phase != BleSessionPhase.Transmitting) return false
        return orchestrator.triggerShutterSequence(identifier)
    }

    fun setRemoteStatusMonitoringEnabled(identifier: String, enabled: Boolean) {
        orchestrator.setRemoteMonitoring(identifier, enabled)
    }

    fun applyDeviceEnabledState(identifier: String, enabled: Boolean) {
        val normalized = identifier.uppercase()
        repository.setDeviceEnabled(normalized, enabled)

        if (!enabled) {
            orchestrator.setRemoteMonitoring(normalized, false)
            controllerScope.launch {
                disconnectInternal(identifier, removeFromAutoReconnect = false)
            }
            return
        }

        // Targeted connect instead of [reconnectToPersistedPeripherals]: only this
        // device changed, and it relies on the already updated in-memory enabled
        // state rather than the sweep's database re-sync.
        // FIXME do we still need this or use reconnectToPersistedPeripherals() instead?
        if (appEnabled) {
            if (shell?.retrieveAndConnect(normalized) == true) {
                refreshDeviceListFrom(shell)
            }
        }
    }

    suspend fun applyAppEnabledState(enabled: Boolean) {
        appEnabled = enabled
        if (enabled) {
            startScan()
            reconnectToPersistedPeripherals()
            orchestrator.locationManager.updateTracking()
            refreshDeviceListFrom(shell)
            return
        }
        forceShutdownAllConnections()
    }

    // ---------------------------------------------------------------------------
    // Shutdown / cleanup
    // ---------------------------------------------------------------------------

    private fun forceShutdownAllConnections() {
        shell?.stopScanIfNeeded()
        shell?.cancelAllKnownConnections()
        orchestrator.shutdownAll()
        transport.detachAll()
        shell?.resolveAllWaitersDisconnected()
        refreshDeviceListFrom(shell)
    }

    /**
     * A peripheral restored already connected gets no didConnectPeripheral —
     * announce it here so the orchestrator runs discovery + handshake. This
     * must not happen during willRestoreState: discoverServices issued before
     * the central is powered on is dropped and the session dead-ends in a
     * discovery timeout. Disabled devices are cancelled instead (also only
     * possible once powered on). Runs before [reconnectToPersistedPeripherals]
     * so the sweep sees restored devices as connected and skips them.
     */
    private suspend fun attachRestoredPeripherals(restored: List<CBPeripheral>) {
        restored.forEach { peripheral ->
            val id = peripheral.identifier.UUIDString
            if (!repository.isDeviceEnabled(id)) {
                shell?.cancelConnection(peripheral)
            } else if (peripheral.state == CBPeripheralStateConnected) {
                transport.attachPeripheral(peripheral)
            }
        }
        refreshDeviceListFrom(shell)
    }

    private suspend fun reconnectToPersistedPeripherals() {
        repository.loadStoreFromDisk()
        repository.migrateLegacyDevicesToDatabase()
        repository.sync()
        // The device database is the source of truth for saved devices; the
        // NSUserDefaults store is only read above to migrate legacy entries.
        val ids = repository.savedDevices.keys.toList()
        if (ids.isEmpty()) return
        val central = shell ?: return
        central.retrievePeripherals(ids).forEach { peripheral ->
            val id = peripheral.identifier.UUIDString
            repository.ensureDeviceRecord(id, accessorySession.displayName(id), peripheral.name)
            if (!repository.isDeviceEnabled(id)) {
                return@forEach
            }
            central.registerAndConnect(peripheral)
        }
        refreshDeviceListFrom(shell)
    }

    // Shell-owned identity/connection state only; per-device session state is
    // observed by the UI straight from [sessions]. Takes the shell as a parameter
    // because the restoration path invokes this while this object's `shell` field
    // is still unassigned (see IosCentralShell's reentrancy contract).
    private fun refreshDeviceListFrom(shell: IosCentralShell?) {
        val persistedByNormalized = repository.savedDevices
        val discoveredByNormalized =
            shell?.discoveredPeripherals.orEmpty().entries.associateBy { it.key.uppercase() }
        val connectedByNormalized =
            shell?.connectedPeripherals?.keys.orEmpty().associateBy { it.uppercase() }
        val allIdentifiers = LinkedHashSet<String>()
        allIdentifiers.addAll(discoveredByNormalized.keys)
        allIdentifiers.addAll(persistedByNormalized.keys)

        _devices.update {
            allIdentifiers.map { normalizedId ->
                val discoveredEntry = discoveredByNormalized[normalizedId]
                val persistedEntry = persistedByNormalized[normalizedId]
                val peripheral = discoveredEntry?.value
                val identifier = discoveredEntry?.key ?: (persistedEntry?.mac ?: normalizedId)
                BluetoothDeviceInfo(
                    identifier = identifier,
                    name = AccessoryCameraName.resolveName(
                        accessoryName = accessorySession.displayName(normalizedId),
                        bluetoothName = peripheral?.name,
                        savedName = persistedEntry?.deviceName,
                        savedNameIsCustom = persistedEntry?.deviceNameIsCustom == true,
                    ),
                    isConnected = connectedByNormalized.containsKey(normalizedId),
                    isSaved = repository.isSaved(identifier),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Policy
    // ---------------------------------------------------------------------------

    private suspend fun shouldAutoReconnect(id: String): Boolean {
        // A camera that rejected pairing is disconnected on purpose; reconnecting
        // would restart the pairing gate and loop the camera's pairing prompt.
        // Cleared when the user dismisses the dialog or a handshake succeeds.
        if (id.equals(pairingFailedIdentifier, ignoreCase = true)) return false
        // A de-authorized accessory can never connect again; retrying would be a
        // reconnect storm against a device CoreBluetooth is not allowed to see.
        if (!accessorySession.isAuthorized(id)) return false
        return appEnabled &&
                repository.isAutoReconnectEnabled(id) &&
                repository.isDeviceEnabled(id)
    }

    // ---------------------------------------------------------------------------
    // AccessorySetupKit
    // ---------------------------------------------------------------------------

    /**
     * Bring the central up because the user wants to use an authorized camera,
     * even though some old cameras have not been migrated yet.
     */
    private fun startCentralForAccessoryUse() {
        if (centralShell != null) return
        // Safe to start even with cameras left to migrate: the migration picker
        // releases the central again when it runs.
        startCentralIfNeeded()
    }

    private fun handleAccessoryAdded(identifier: String, displayName: String?) {
        controllerScope.launch {
            startCentralForAccessoryUse()
            withDatabase("accessory added") {
                val hardwareName = shell?.peripheralName(identifier)
                    ?: shell?.retrieveNames(listOf(identifier))?.get(identifier.uppercase())
                repository.ensureDeviceRecord(identifier, displayName, hardwareName)
                repository.sync()
            }
            repository.markAutoReconnect(identifier)
            shell?.retrieveAndConnect(identifier)
            // The user may have re-paired a camera rather than migrating it;
            // without this it would sit in the candidate list for ever.
            accessories.recomputeMigrationCandidates()
            refreshDeviceListFrom(shell)
        }
    }

    /** The user unpaired the camera in Settings; drop our row so the two agree. */
    private fun handleAccessoryRemoved(identifier: String) {
        controllerScope.launch {
            val normalized = identifier.uppercase()
            disconnectInternal(normalized, removeFromAutoReconnect = true)
            shell?.forget(normalized)
            withDatabase("accessory removed") {
                repository.deleteDevice(normalized)
                repository.sync()
            }
            refreshDeviceListFrom(shell)
        }
    }

    /**
     * True when the camera is an authorized AccessorySetupKit accessory, and can
     * therefore be renamed in the system record instead of only in the app.
     * Cameras paired before AccessorySetupKit have no accessory record, so they
     * fall back to the shared in-app rename dialog.
     */
    fun canRenameInSystem(identifier: String): Boolean =
        accessorySession.isAuthorized(identifier)

    /**
     * Present Apple's rename sheet. The chosen name is not returned here; it
     * arrives as an accessoryChanged event and is persisted by
     * [persistAccessoryNames].
     */
    fun presentSystemRename(identifier: String) {
        controllerScope.launch { accessorySession.rename(identifier) }
    }

    /** Redraw the device list after an in-app rename wrote straight to the DAO. */
    fun refreshDeviceNames() {
        controllerScope.launch {
            withDatabase("device renamed") { repository.sync() }
            refreshDeviceListFrom(shell)
        }
    }

    /** Persist names from a refreshed accessory snapshot, then redraw the list. */
    private fun persistAccessoryNames() {
        val shell = shell
        controllerScope.launch {
            withDatabase("accessory names changed") {
                repository.savedDevices.keys.toList().forEach { id ->
                    repository.ensureDeviceRecord(
                        id,
                        accessorySession.displayName(id),
                        shell?.peripheralName(id),
                    )
                }
                repository.sync()
            }
            refreshDeviceListFrom(shell)
        }
        refreshDeviceListFrom(shell)
    }

    suspend fun ensureDeviceRecord(identifier: String, deviceName: String? = null) {
        repository.ensureDeviceRecord(
            identifier,
            accessoryName = accessorySession.displayName(identifier),
            bluetoothName = shell?.peripheralName(identifier) ?: deviceName,
        )
        repository.sync()
        refreshDeviceListFrom(shell)
    }
}
