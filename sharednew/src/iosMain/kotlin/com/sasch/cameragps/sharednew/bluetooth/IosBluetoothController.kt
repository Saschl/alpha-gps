package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.VariableLogLevel
import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.IosAppPreferences
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.ensureInitialized
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.retryAfterPairing
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.database.logging.DatabaseLogger
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBConnectPeripheralOptionNotifyOnConnectionKey
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * iOS Bluetooth controller backed by CoreBluetooth + CoreLocation.
 *
 * This is a **singleton** because the [CBCentralManager] must be created with
 * the same [CBCentralManagerOptionRestoreIdentifierKey] on every app launch –
 * including background launches triggered by CoreBluetooth state restoration.
 * Tying the manager to a Compose `remember {}` block would delay creation past
 * the restoration window or lose it entirely when the composable is disposed.
 *
 * Call [ensureInitialized] as early as possible (e.g. from an AppDelegate) so
 * the manager is ready before iOS delivers `willRestoreState`.
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
        // Accessing `central` is enough – the property initialiser creates the
        // CBCentralManager and registers it for state restoration.
        central
        KmLogging.setLoggers(
            DatabaseLogger(
                LogRepository(getDatabaseBuilder()),
                VariableLogLevel(LogLevel.valueOf(IosAppPreferences.getLogLevel()))
            )
        )
    }

    private val logging = logging()

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices

    override val capabilities: Set<BluetoothCapability> = setOf(
        BluetoothCapability.Scan,
        BluetoothCapability.Connect,
        BluetoothCapability.ObserveConnection,
    )

    // UUID string -> CBPeripheral
    private val discovered = mutableMapOf<String, CBPeripheral>()
    private val connected = mutableMapOf<String, CBPeripheral>()
    private val sessions = mutableMapOf<String, PeripheralSession>()

    // Pending callbacks waiting for a connect/disconnect result
    private val connectCallbacks = mutableMapOf<String, (Boolean) -> Unit>()
    private val disconnectCallbacks = mutableMapOf<String, () -> Unit>()

    // ---- State-restoration / auto-reconnect --------------------------------
    /** Peripheral UUIDs that should be reconnected automatically (i.e. the user
     *  has connected them at least once and has not explicitly disconnected). */
    private val autoReconnectIds = mutableSetOf<String>()
    private val userDefaults = NSUserDefaults.standardUserDefaults
    private val persistedPeripheralsKey = "com.saschl.cameragps.persistedPeripherals"
    private const val MAX_IMMEDIATE_FIX_AGE_SECONDS = 5 * 60L
    private var appEnabled = IosAppPreferences.isAppEnabled()
    // ------------------------------------------------------------------------

    private var latestLocation: CLLocation? = null
    private var transmissionJob: Job? = null
    private var locationUpdatesStarted = false

    // True once Core Location delivers a live fix in the current tracking session.
    // A stationary user's fix never refreshes, so isFreshFix() would eventually
    // return false even though the location is still correct. We use this flag to
    // trust any fix that was live when we received it, regardless of its age.
    private var hasSessionLocation = false

    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {
        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            peripheral.services?.forEach { service ->
                peripheral.discoverCharacteristics(
                    characteristicUUIDs = null,
                    forService = service as CBService
                )
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            val session =
                sessions.getOrPut(peripheral.identifier.UUIDString) { PeripheralSession(peripheral) }

            didDiscoverCharacteristicsForService.characteristics?.forEach { characteristicAny ->
                val characteristic = characteristicAny as CBCharacteristic
                val uuid = characteristic.UUID
                logging.v {
                    "  Discovered characteristic $uuid  props=0x${
                        characteristic.properties.toString(
                            16
                        )
                    }"
                }

                when (uuid) {
                    IosSonyBleConstants.LOCATION_CHARACTERISTIC_UUID_STRING -> session.locationWriteCharacteristic =
                        characteristic

                    IosSonyBleConstants.READ_CHARACTERISTIC_UUID_STRING -> session.readCharacteristic =
                        characteristic

                    IosSonyBleConstants.ENABLE_UNLOCK_GPS_UUID_STRING -> session.unlockGpsCharacteristic =
                        characteristic

                    IosSonyBleConstants.ENABLE_LOCK_GPS_UUID_STRING -> session.lockGpsCharacteristic =
                        characteristic

                    IosSonyBleConstants.TIME_SYNC_CHARACTERISTIC_UUID_STRING -> session.timeSyncCharacteristic =
                        characteristic

                    IosSonyBleConstants.LOCATION_ENABLED_CHARACTERISTIC_UUID_STRING -> session.locationEnabledCharacteristic =
                        characteristic
                }

                // Collect every characteristic that supports notifications/indications
                // so we can pick the best one for pairing.
                val supportsNotify =
                    (characteristic.properties and CBCharacteristicPropertyNotify) != 0uL ||
                            (characteristic.properties and CBCharacteristicPropertyIndicate) != 0uL
                if (supportsNotify) {
                    session.notifiableCharacteristics.add(characteristic)
                }
            }

            // Trigger pairing by subscribing to notifications.
            // The CCCD descriptor write that setNotifyValue triggers often requires
            // authentication on BLE peripherals, which prompts the iOS system
            // pairing dialog for unpaired devices.
            // We only try characteristics that actually support notifications.
            if (session.phase == PeripheralPhase.Connected) {
                val pairingTarget = session.notifiableCharacteristics.firstOrNull()

                if (pairingTarget != null) {
                    session.phase = PeripheralPhase.WaitingForPairing
                    logging(
                        "Subscribing to notifications on ${pairingTarget.UUID.UUIDString} (props=0x${
                            pairingTarget.properties.toString(
                                16
                            )
                        }) to trigger pairing"
                    )
                    peripheral.setNotifyValue(true, forCharacteristic = pairingTarget)
                } else {
                    logging.d { "No notifiable characteristic discovered yet – proceeding with normal flow (pairing will be attempted on first encrypted read/write)" }
                    proceedAfterPairing(session, peripheral)
                }
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val session = sessions[peripheral.identifier.UUIDString] ?: return

            // iOS triggers the system pairing dialog automatically when an
            // encrypted characteristic is read, but the callback still fires
            // with an authentication error. Retry after a delay so the user
            // has time to accept the pairing dialog.
            logging.v { "Read value for ${didUpdateValueForCharacteristic.UUID.UUIDString} (error=${error?.code} / ${error?.localizedDescription})" }
            if (isAuthenticationError(error)) {
                retryAfterPairing(session) {
                    peripheral.readValueForCharacteristic(didUpdateValueForCharacteristic)
                }
                return
            }

            val value = didUpdateValueForCharacteristic.value?.toByteArray() ?: return

            // Pairing succeeded (or was not needed) – reset the retry counter.
            session.pairingRetryCount = 0

            if (didUpdateValueForCharacteristic.UUID == IosSonyBleConstants.READ_CHARACTERISTIC_UUID_STRING) {
                session.locationConfig = SonyLocationTransmissionConfig(
                    shouldSendTimeZoneAndDst = SonyLocationTransmissionUtils.hasTimeZoneDstFlag(
                        value
                    ),
                )
                beginGpsEnable(session)
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val session = sessions[peripheral.identifier.UUIDString] ?: return

            // Handle pairing-related authentication errors. iOS shows the
            // system pairing dialog automatically; we restart the GPS-enable
            // flow after a delay so the user has time to accept.
            logging.v { "Write value for ${didWriteValueForCharacteristic.UUID.UUIDString} completed (error=${error?.code} / ${error?.localizedDescription})" }
            if (isAuthenticationError(error)) {
                retryAfterPairing(session) {
                    // Reset phase so beginGpsEnable's guard doesn't skip it.
                    session.phase = PeripheralPhase.Connected
                    beginGpsEnable(session)
                }
                return
            }

            if (error != null) {
                logging.e { "BLE write failed for ${didWriteValueForCharacteristic.UUID.UUIDString}: ${error.localizedDescription}" }
                return
            }

            // Write succeeded – reset the pairing retry counter.
            session.pairingRetryCount = 0

            when (didWriteValueForCharacteristic.UUID) {
                IosSonyBleConstants.ENABLE_UNLOCK_GPS_UUID_STRING -> {
                    val lockCharacteristic = session.lockGpsCharacteristic
                    if (lockCharacteristic != null) {
                        session.phase = PeripheralPhase.LockingGps
                        peripheral.writeValue(
                            data = SonyBluetoothConstants.GPS_ENABLE_COMMAND.toNSData(),
                            forCharacteristic = lockCharacteristic,
                            type = CBCharacteristicWriteWithResponse,
                        )
                    } else {
                        beginTimeSyncOrTransmission(session)
                    }
                }

                IosSonyBleConstants.ENABLE_LOCK_GPS_UUID_STRING -> beginTimeSyncOrTransmission(
                    session
                )

                IosSonyBleConstants.TIME_SYNC_CHARACTERISTIC_UUID_STRING -> markReadyForTransmission(
                    session
                )
            }
        }

        /**
         * Called after [CBPeripheral.setNotifyValue] completes. We use this as
         * the pairing trigger: subscribing to notifications writes the CCCD
         * descriptor, which on many BLE peripherals requires authentication.
         * If the device is not yet paired iOS shows the system pairing dialog
         * automatically and – on success – retries the write internally, so
         * this callback fires once with `error == null`.
         *
         * If pairing is rejected the callback fires with an authentication
         * error and we retry with [retryAfterPairing].
         */
        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val session = sessions[peripheral.identifier.UUIDString] ?: return

            // Ignore callbacks that arrive when we're no longer waiting for
            // pairing (e.g. the unsubscribe we issue after pairing succeeds).
            if (session.phase != PeripheralPhase.WaitingForPairing) return
            logging.d { "Notification subscription result for ${didUpdateNotificationStateForCharacteristic.UUID.UUIDString}: error=${error?.code} / ${error?.localizedDescription}" }

            if (isAuthenticationError(error)) {
                retryAfterPairing(session) {
                    peripheral.setNotifyValue(
                        true,
                        forCharacteristic = didUpdateNotificationStateForCharacteristic,
                    )
                }
                return
            }

            // Subscription succeeded (pairing done or wasn't needed) or failed
            // with a non-auth error (e.g. notifications not supported). Either
            // way, reset the retry counter and continue with the normal flow.
            session.pairingRetryCount = 0


            if (error != null) {
                logging.d { "Notification subscription failed (non-auth, code=${error.code}): ${error.localizedDescription} – continuing anyway" }
            } else {
                logging.d { "Notification subscription succeeded – device is paired" }
                // Unsubscribe; we only needed the CCCD write for pairing.
                peripheral.setNotifyValue(
                    false,
                    forCharacteristic = didUpdateNotificationStateForCharacteristic,
                )
            }

            // Proceed to the normal config-read / GPS-enable flow.
            proceedAfterPairing(session, peripheral)
        }
    }

    private val locationDelegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            logging.d { "Received new location" }

            if (!isAppEnabledForTransmission()) return

            if (!shouldUpdateLocation(location)) return

            hasSessionLocation = true

            // send initial location immediately if none was cached yet
            if (latestLocation == null || !isFreshFix(latestLocation!!)) {
                runCatching {
                    sendLocationToReadyPeripherals(location)
                }.onFailure {
                    logging.e(it, msg = { "Error sending location to peripherals" })
                }
            }
            latestLocation = location
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            logging.e { "Location" }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (manager.authorizationStatus() == kCLAuthorizationStatusAuthorizedWhenInUse) {
                manager.requestAlwaysAuthorization()
            }
            updateLocationTracking()
        }
    }

    private val locationManager = CLLocationManager().apply {
        delegate = locationDelegate
        desiredAccuracy = platform.CoreLocation.kCLLocationAccuracyBest
        distanceFilter = 10.0

        pausesLocationUpdatesAutomatically = false
        allowsBackgroundLocationUpdates = true
        //showsBackgroundLocationIndicator = true
    }

    // ---------------------------------------------------------------------------
    // CoreBluetooth delegate
    // ---------------------------------------------------------------------------
    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                if (!appEnabled) {
                    stopScanIfNeeded()
                    cancelAllKnownConnections()
                    refreshDeviceList()
                    return
                }
                if (!central.isScanning) {
                    central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
                }
                // Re-issue pending connect requests for every peripheral the user previously
                // connected to. CoreBluetooth will keep retrying until it succeeds or the
                // connection is explicitly cancelled – this is what drives the "connect when
                // in range" behaviour after an app kill / BT power-cycle.
                reconnectToPersistedPeripherals()
            }
        }

        /**
         * Called by iOS when the app is relaunched in the background to restore a
         * previously-active CBCentralManager session (state preservation & restoration).
         *
         * Any peripherals that were connected (or had a pending connection) when the
         * app was killed are handed back here. We re-attach our delegate so that
         * characteristic discovery and GPS transmission resume seamlessly.
         */
        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            @Suppress("UNCHECKED_CAST")
            val restoredPeripherals =
                willRestoreState[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>
                    ?: return

            if (appEnabled) {
                restoredPeripherals.forEach { any ->
                    val peripheral = any as? CBPeripheral ?: return@forEach
                    val id = peripheral.identifier.UUIDString
                    discovered[id] = peripheral
                    // Re-attach our peripheral delegate so callbacks keep working.
                    peripheral.delegate = peripheralDelegate

                    if (peripheral.state == CBPeripheralStateConnected) {
                        // The OS kept the connection alive – resume service discovery.
                        connected[id] = peripheral
                        val session = sessions.getOrPut(id) { PeripheralSession(peripheral) }
                        session.phase = PeripheralPhase.Connected
                        peripheral.discoverServices(
                            listOf(
                                CBUUID.UUIDWithString(SonyBluetoothConstants.SERVICE_UUID),
                                CBUUID.UUIDWithString(SonyBluetoothConstants.CONTROL_SERVICE_UUID),
                            )
                        )
                    }
                    // If not yet connected, CoreBluetooth still has the pending connection
                    // request alive and will fire didConnectPeripheral when the device is found.
                }
                refreshDeviceList()
            } else {
                restoredPeripherals.forEach { any ->
                    val peripheral = any as? CBPeripheral ?: return@forEach
                    if (central.state == CBManagerStatePoweredOn) {
                        central.cancelPeripheralConnection(peripheral)
                    }
                }
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            // The manufacturer data blob starts with a 2-byte company ID
            // (little-endian). Only accept devices whose company ID matches Sony.
            val mfgData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData
            if (mfgData == null || mfgData.length < 2u) return

            val bytes = mfgData.toByteArray()
            val companyId = (bytes[0].toInt() and 0xFF) or
                    ((bytes[1].toInt() and 0xFF) shl 8)
            if (companyId != 0x012D) return

            val id = didDiscoverPeripheral.identifier.UUIDString
            discovered[id] = didDiscoverPeripheral
            refreshDeviceList()
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral,
        ) {
            val id = didConnectPeripheral.identifier.UUIDString
            connected[id] = didConnectPeripheral
            // Remember this device so we can reconnect after app kill / BT toggle.
            persistConnectedPeripheral(id)
            val session = sessions.getOrPut(id) { PeripheralSession(didConnectPeripheral) }
            session.phase = PeripheralPhase.Connected
            didConnectPeripheral.delegate = peripheralDelegate
            didConnectPeripheral.discoverServices(
                listOf(
                    IosSonyBleConstants.LOCATION_SERVICE_UUID,
                    IosSonyBleConstants.CONTROL_SERVICE_UUID_STRING,
                )
            )
            connectCallbacks.remove(id)?.invoke(true)
            refreshDeviceList()
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val id = didFailToConnectPeripheral.identifier.UUIDString
            connectCallbacks.remove(id)?.invoke(false)

            // Re-issue the connection request for persisted devices so
            // CoreBluetooth keeps retrying in the background.
            if (shouldAutoReconnect(id)) {
                central.connectPeripheral(didFailToConnectPeripheral, options = null)
            }
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val id = didDisconnectPeripheral.identifier.UUIDString
            connected.remove(id)
            sessions.remove(id)
            connectCallbacks.remove(id)?.invoke(false)
            disconnectCallbacks.remove(id)?.invoke()

            // If this was an *unexpected* disconnect (i.e. the user didn't call
            // disconnect()), queue a new connection attempt. CoreBluetooth will
            // keep retrying silently in the background until the device is found –
            // even across app kills, thanks to state preservation.
            if (shouldAutoReconnect(id)) {
                central.connectPeripheral(didDisconnectPeripheral, options = null)
            }

            updateLocationTracking()
            refreshDeviceList()
        }
    }

    private val central = CBCentralManager(
        delegate = delegate,
        queue = null,
        // State preservation: iOS uses this key to match the relaunched app's
        // CBCentralManager with the one that was alive before the kill, handing
        // back connected / pending peripherals via willRestoreState.
        options = mapOf(CBCentralManagerOptionRestoreIdentifierKey to "com.saschl.cameragps.central"),
    )

    // ---------------------------------------------------------------------------
    // BluetoothController implementation
    // ---------------------------------------------------------------------------

    override suspend fun startScan() {
        if (central.state == CBManagerStatePoweredOn && !central.isScanning) {
             central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
        }
    }

    override suspend fun stopScan() {
        if (central.state == CBManagerStatePoweredOn) {
            central.stopScan()
        }
    }

    override suspend fun connect(identifier: String): Boolean {
        val peripheral = discovered[identifier] ?: return false
        if (connected.containsKey(identifier)) return true

        if (central.state != CBManagerStatePoweredOn) {
            logging.e { "Cannot connect: CBCentralManager is not powered on (state=${central.state})" }
            return false
        }

        return suspendCancellableCoroutine { cont ->
            connectCallbacks[identifier] = { success ->
                if (cont.isActive) cont.resume(success)
            }
            central.connectPeripheral(peripheral, options = null)

            cont.invokeOnCancellation {
                connectCallbacks.remove(identifier)
                if (central.state == CBManagerStatePoweredOn) {
                    central.cancelPeripheralConnection(peripheral)
                }
            }
        }
    }

    override suspend fun disconnect(identifier: String) {
        // Remove from persistence BEFORE cancelling so that the didDisconnect
        // callback does not immediately queue a reconnect.
        removePersistedPeripheral(identifier)

        val peripheral = connected[identifier] ?: discovered[identifier] ?: return

        if (central.state != CBManagerStatePoweredOn) {
            // Can't send the cancel command – just clean up local state.
            connected.remove(identifier)
            sessions.remove(identifier)
            updateLocationTracking()
            refreshDeviceList()
            return
        }

        suspendCancellableCoroutine { cont ->
            disconnectCallbacks[identifier] = {
                if (cont.isActive) cont.resume(Unit)
            }
            central.cancelPeripheralConnection(peripheral)

            cont.invokeOnCancellation {
                disconnectCallbacks.remove(identifier)
            }
        }
    }

    override suspend fun forgetDevice(identifier: String) {
        // Disconnect first (also removes from persistence and connected map).
        // disconnect() is a no-op if the peripheral isn't currently connected.
        disconnect(identifier)
        // Remove the peripheral from the discovered pool so it vanishes from the UI.
        discovered.remove(identifier)
        sessions.remove(identifier)
        refreshDeviceList()
    }

    suspend fun applyAppEnabledState(enabled: Boolean) {
        appEnabled = enabled
        if (enabled) {
            startScan()
            reconnectToPersistedPeripherals()
            updateLocationTracking()
            refreshDeviceList()
            return
        }

        forceShutdownAllConnections()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Returns `true` when the Core Bluetooth ATT error indicates that the
     * peripheral requires pairing / bonding before the operation can succeed.
     * iOS shows the system pairing dialog automatically, but the original
     * operation's callback still receives one of these errors.
     */
    private fun isAuthenticationError(error: NSError?): Boolean {
        logging.v { "error, if any: ${error?.code} / ${error?.localizedDescription}" }

        if (error == null) return false
        return error.code == IosSonyBleConstants.ATT_ERROR_INSUFFICIENT_AUTHENTICATION ||
                error.code == IosSonyBleConstants.ATT_ERROR_INSUFFICIENT_ENCRYPTION
    }

    /**
     * Schedules [operation] to run after a delay, giving the user time to
     * accept the iOS system pairing dialog. If the maximum number of retries
     * has been reached the peripheral is disconnected instead.
     */
    private fun retryAfterPairing(
        session: PeripheralSession,
        operation: () -> Unit,
    ) {
        if (session.pairingRetryCount >= IosSonyBleConstants.MAX_PAIRING_RETRIES) {
            logging.e { "Pairing failed after ${IosSonyBleConstants.MAX_PAIRING_RETRIES} retries, disconnecting ${session.peripheral.name}" }
            if (central.state == CBManagerStatePoweredOn) {
                central.cancelPeripheralConnection(session.peripheral)
            }
            return
        }
        session.pairingRetryCount++
        session.phase = PeripheralPhase.WaitingForPairing
        logging.d { "Authentication error – iOS pairing may be in progress (attempt ${session.pairingRetryCount}/${IosSonyBleConstants.MAX_PAIRING_RETRIES}), retrying in ${IosSonyBleConstants.PAIRING_RETRY_DELAY_MS}ms" }
        controllerScope.launch {
            delay(IosSonyBleConstants.PAIRING_RETRY_DELAY_MS)
            operation()
        }
    }

    /**
     * Continues the normal connection flow after the pairing step
     * (notification subscription) has completed or been skipped.
     */
    private fun proceedAfterPairing(session: PeripheralSession, peripheral: CBPeripheral) {
        when {
            session.locationConfig == null && session.readCharacteristic != null -> {
                session.phase = PeripheralPhase.ReadingConfig
                peripheral.readValueForCharacteristic(session.readCharacteristic!!)
            }

            session.locationConfig == null -> {
                session.locationConfig =
                    SonyLocationTransmissionConfig(shouldSendTimeZoneAndDst = false)
                session.phase = PeripheralPhase.Connected
                beginGpsEnable(session)
            }

            else -> {
                session.phase = PeripheralPhase.Connected
                beginGpsEnable(session)
            }
        }
    }

    private fun beginGpsEnable(session: PeripheralSession) {
        if (session.phase == PeripheralPhase.EnablingGps || session.phase == PeripheralPhase.LockingGps || session.phase == PeripheralPhase.SyncingTime || session.phase == PeripheralPhase.Ready || session.phase == PeripheralPhase.WaitingForPairing) {
            return
        }

        val unlockCharacteristic = session.unlockGpsCharacteristic
        if (unlockCharacteristic != null) {
            session.phase = PeripheralPhase.EnablingGps
            session.peripheral.writeValue(
                data = SonyBluetoothConstants.GPS_ENABLE_COMMAND.toNSData(),
                forCharacteristic = unlockCharacteristic,
                type = CBCharacteristicWriteWithResponse,
            )
        } else {
            beginTimeSyncOrTransmission(session)
        }
    }

    private fun beginTimeSyncOrTransmission(session: PeripheralSession) {
        val timeSyncCharacteristic = session.timeSyncCharacteristic
        if (timeSyncCharacteristic != null) {
            session.phase = PeripheralPhase.SyncingTime
            session.peripheral.writeValue(
                data = SonyLocationTransmissionUtils.buildTimeSyncPacket().toNSData(),
                forCharacteristic = timeSyncCharacteristic,
                type = CBCharacteristicWriteWithResponse,
            )
        } else {
            markReadyForTransmission(session)
        }
    }

    private fun markReadyForTransmission(session: PeripheralSession) {
        session.phase = PeripheralPhase.Ready
        updateLocationTracking()
        refreshDeviceList()

        if (!isAppEnabledForTransmission()) return

        latestLocation?.let {
            if (hasSessionLocation || isFreshFix(it)) {
                sendLocationToPeripheral(session, it)
            }
        }
    }

    private fun updateLocationTracking() {
        val appEnabled = isAppEnabledForTransmission()
        val hasReadyPeripheral = sessions.values.any { it.phase == PeripheralPhase.Ready }
        if (!hasReadyPeripheral || !appEnabled) {
            if (locationUpdatesStarted) {
                locationManager.stopUpdatingLocation()
                locationUpdatesStarted = false
                hasSessionLocation = false
            }
            transmissionJob?.cancel()
            transmissionJob = null
            refreshDeviceList()
            return
        }

        if (!locationUpdatesStarted) {
            /*  serviceSession = CLServiceSession.sessionRequiringAuthorization(
                  CLServiceSessionAuthorizationRequirementAlways
              )
              backgroundActivitySession = CLBackgroundActivitySession.backgroundActivitySession()*/
            locationManager.requestWhenInUseAuthorization()
            locationManager.startUpdatingLocation()
            // Prime the first fix quickly so transmission can start without waiting for the next interval.
            //locationManager.requestLocation()
            locationUpdatesStarted = true
        }

        if (transmissionJob == null) {
            transmissionJob = controllerScope.launch {
                while (isActive) {
                    delay(SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS)
                    logging.d { "Periodic timer triggered – sending location to ready peripherals" }
                    runCatching {
                        latestLocation?.let { sendLocationToReadyPeripherals(it) }
                    }.onFailure { e ->
                        NSLog("error, %s", e.toString())
                    }
                }
            }
        }

        refreshDeviceList()
    }

    private fun sendLocationToReadyPeripherals(location: CLLocation) {
        sessions.values
            .filter { it.phase == PeripheralPhase.Ready }
            .forEach { sendLocationToPeripheral(it, location) }
    }

    private fun sendLocationToPeripheral(session: PeripheralSession, location: CLLocation) {
        val characteristic = session.locationWriteCharacteristic ?: return
        val config = session.locationConfig ?: SonyLocationTransmissionConfig(false)
        session.peripheral.writeValue(
            data = SonyLocationTransmissionUtils.buildLocationDataPacket(config, location)
                .toNSData(),
            forCharacteristic = characteristic,
            type = CBCharacteristicWriteWithResponse,
        )
    }

    private fun forceShutdownAllConnections() {
        stopScanIfNeeded()
        cancelAllKnownConnections()

        connectCallbacks.values.forEach { callback -> callback(false) }
        connectCallbacks.clear()
        disconnectCallbacks.values.forEach { callback -> callback() }
        disconnectCallbacks.clear()

        connected.clear()
        sessions.clear()
        latestLocation = null
        hasSessionLocation = false

        if (locationUpdatesStarted) {
            locationManager.stopUpdatingLocation()
            locationUpdatesStarted = false
        }
        transmissionJob?.cancel()
        transmissionJob = null

        refreshDeviceList()
    }

    private fun cancelAllKnownConnections() {
        if (central.state != CBManagerStatePoweredOn) return

        val peripherals = mutableMapOf<String, CBPeripheral>()
        connected.forEach { (id, peripheral) -> peripherals[id] = peripheral }
        discovered.forEach { (id, peripheral) -> peripherals[id] = peripheral }
        sessions.forEach { (id, session) -> peripherals[id] = session.peripheral }

        peripherals.values.forEach { peripheral ->
            central.cancelPeripheralConnection(peripheral)
        }
    }

    private fun stopScanIfNeeded() {
        if (central.state == CBManagerStatePoweredOn && central.isScanning) {
            central.stopScan()
        }
    }

    // ---------------------------------------------------------------------------
    // State-restoration / auto-reconnect helpers
    // ---------------------------------------------------------------------------

    /** Saves [id] to both the in-memory set and NSUserDefaults. */
    private fun persistConnectedPeripheral(id: String) {
        autoReconnectIds.add(id)
        flushPersistedIds()
    }

    /** Removes [id] from the in-memory set and NSUserDefaults (called on explicit disconnect). */
    private fun removePersistedPeripheral(id: String) {
        autoReconnectIds.remove(id)
        flushPersistedIds()
    }

    private fun flushPersistedIds() {
        userDefaults.setObject(
            autoReconnectIds.joinToString(","),
            forKey = persistedPeripheralsKey,
        )
        userDefaults.synchronize()
    }

    private fun loadPersistedIds(): Set<String> {
        val raw = userDefaults.stringForKey(persistedPeripheralsKey) ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    /**
     * Attempts to reconnect to every peripheral UUID stored in NSUserDefaults.
     *
     * `CBCentralManager.retrievePeripheralsWithIdentifiers` looks up peripherals
     * the OS already knows about (paired / previously seen). If found, we call
     * `connectPeripheral` which CoreBluetooth keeps active indefinitely in the
     * background – even surviving app kills when state preservation is enabled.
     */
    private fun reconnectToPersistedPeripherals() {
        val ids = loadPersistedIds()
        if (ids.isEmpty()) return

        autoReconnectIds.addAll(ids)

        val nsuuids = ids.map { NSUUID(uUIDString = it) }
        val peripherals = central.retrievePeripheralsWithIdentifiers(nsuuids)

        peripherals.forEach { any ->
            val peripheral = any as? CBPeripheral ?: return@forEach
            val id = peripheral.identifier.UUIDString
            discovered[id] = peripheral
            if (!connected.containsKey(id)) {
                central.connectPeripheral(
                    peripheral,
                    options = mapOf(CBConnectPeripheralOptionNotifyOnConnectionKey to true)
                )
            }
        }
        refreshDeviceList()
    }

    private fun refreshDeviceList() {
        _devices.update {
            discovered.map { (id, peripheral) ->
                val session = sessions[id]
                BluetoothDeviceInfo(
                    identifier = id,
                    name = peripheral.name ?: "Unknown device",
                    isConnected = connected.containsKey(id),
                    isSaved = id in autoReconnectIds,
                    isTransmissionActive =
                        session?.phase == PeripheralPhase.Ready && locationUpdatesStarted,
                )
            }
        }
    }

    private fun shouldAutoReconnect(id: String): Boolean {
        return appEnabled && id in autoReconnectIds && central.state == CBManagerStatePoweredOn
    }

    private fun isAppEnabledForTransmission(): Boolean = appEnabled

    private fun shouldUpdateLocation(newLocation: CLLocation): Boolean {
        val current = latestLocation ?: return true

        // Unknown accuracy values should not block updates.
        if (newLocation.horizontalAccuracy < 0 || current.horizontalAccuracy < 0) {
            return true
        }

        val accuracyDifference = newLocation.horizontalAccuracy - current.horizontalAccuracy
        if (accuracyDifference <= SonyBluetoothConstants.ACCURACY_THRESHOLD_METERS) {
            return true
        }

        val ageMs =
            (newLocation.timestamp.timeIntervalSince1970 - current.timestamp.timeIntervalSince1970) * 1000.0
        return ageMs > SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS
    }

    private fun isFreshFix(location: CLLocation): Boolean {
        val nowSeconds = NSDate().timeIntervalSince1970.toLong()
        val locationSeconds = location.timestamp.timeIntervalSince1970.toLong()
        val ageSeconds = nowSeconds - locationSeconds
        return ageSeconds <= MAX_IMMEDIATE_FIX_AGE_SECONDS
    }
}

private enum class PeripheralPhase {
    Connected,
    ReadingConfig,
    WaitingForPairing,
    EnablingGps,
    LockingGps,
    SyncingTime,
    Ready,
}

@OptIn(ExperimentalForeignApi::class)
private data class PeripheralSession(
    val peripheral: CBPeripheral,
    var locationWriteCharacteristic: CBCharacteristic? = null,
    var readCharacteristic: CBCharacteristic? = null,
    var unlockGpsCharacteristic: CBCharacteristic? = null,
    var lockGpsCharacteristic: CBCharacteristic? = null,
    var timeSyncCharacteristic: CBCharacteristic? = null,
    var locationEnabledCharacteristic: CBCharacteristic? = null,
    var locationConfig: SonyLocationTransmissionConfig? = null,
    var phase: PeripheralPhase = PeripheralPhase.Connected,
    var pairingRetryCount: Int = 0,
    val notifiableCharacteristics: MutableList<CBCharacteristic> = mutableListOf(),
)

private object IosSonyBleConstants {
    val LOCATION_SERVICE_UUID = CBUUID.UUIDWithString(SonyBluetoothConstants.SERVICE_UUID)
    val CONTROL_SERVICE_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.CONTROL_SERVICE_UUID)
    val LOCATION_CHARACTERISTIC_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.CHARACTERISTIC_UUID)
    val READ_CHARACTERISTIC_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.CHARACTERISTIC_READ_UUID)
    val ENABLE_UNLOCK_GPS_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND)
    val ENABLE_LOCK_GPS_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND)
    val TIME_SYNC_CHARACTERISTIC_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.TIME_SYNC_CHARACTERISTIC_UUID)
    val LOCATION_ENABLED_CHARACTERISTIC_UUID_STRING =
        CBUUID.UUIDWithString(SonyBluetoothConstants.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA)

    // ATT error codes that indicate the device requires pairing/bonding.
    // iOS shows the system pairing dialog automatically when an encrypted
    // characteristic is accessed, but the read/write callback still fires
    // with one of these errors. We retry after a short delay so that the
    // user has time to accept the dialog.
    const val ATT_ERROR_INSUFFICIENT_AUTHENTICATION = 5L
    const val ATT_ERROR_INSUFFICIENT_ENCRYPTION = 15L
    const val MAX_PAIRING_RETRIES = 3
    const val PAIRING_RETRY_DELAY_MS = 3_000L
}


@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)

    return ByteArray(size).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}
