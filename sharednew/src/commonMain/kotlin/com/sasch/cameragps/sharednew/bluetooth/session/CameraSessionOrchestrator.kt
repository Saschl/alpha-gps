package com.sasch.cameragps.sharednew.bluetooth.session

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionCoordinator
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionEvent
import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteCommand
import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteControlCoordinator
import com.sasch.cameragps.sharednew.bluetooth.location.LocationEvent
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import com.sasch.cameragps.sharednew.bluetooth.location.LocationTransmissionManager
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationQueue
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationResult
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.remote.wifi.CameraWifiCredentials
import com.sasch.cameragps.sharednew.remote.wifi.SonyWifiBootstrap
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteConnectException
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The common session orchestrator. Owns the per-device session registry, the
 * sequential operation queue and the location transmission manager; routes
 * transport events into the existing shared [BleSessionCoordinator] and
 * [RemoteControlCoordinator].
 *
 * This unifies what `LocationSenderService` (+ its adapter coordinators) did
 * on Android and what `IosBluetoothController`'s delegates/init-block did on
 * iOS. Platform shells only own sockets and lifecycle:
 * they feed connects/disconnects in via the transport and react to [events].
 *
 * Must be driven from a single-threaded [scope]
 * (`Dispatchers.Main.immediate` on both platforms).
 */
class CameraSessionOrchestrator(
    private val transport: BlePeripheralTransport,
    locationSource: LocationSource,
    private val deviceDao: CameraDeviceDAO,
    private val scope: CoroutineScope,
    private val pairingPolicy: PairingRetryPolicy = PairingRetryPolicy(),
    /**
     * Consulted when a handshake completes. Return `false` to abort the session
     * (iOS passes its device-enabled check here and disconnects inside the
     * lambda when disabled).
     */
    private val shouldRemainConnected: suspend (String) -> Boolean = { true },
    /** iOS: app-level transmission toggle for the location manager. */
    isTransmissionAllowed: () -> Boolean = { true },
) : CameraAutoCorrectionControls {
    private val log = logging()

    val registry = CameraSessionRegistry()

    private val queue: BleOperationQueue =
        BleOperationQueue(transport, scope, shouldExecute = { id, operation ->
            // Parked location packets require a completed handshake. Camera-reported
            // location status is advisory and does not gate transmission.
            when {
                operation !is BleOperation.Write -> true
                operation.characteristicUuid.equals(SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID, true) &&
                    registry.get(id)?.wifiRemote?.phase?.let {
                        it != com.sasch.cameragps.sharednew.remote.wifi.WifiRemotePhase.Idle &&
                            it != com.sasch.cameragps.sharednew.remote.wifi.WifiRemotePhase.Failed
                    } == true -> listOf(SonyBluetoothConstants.FULL_SHUTTER_UP_COMMAND,
                        SonyBluetoothConstants.HALF_SHUTTER_UP_COMMAND, SonyBluetoothConstants.AF_ON_UP_COMMAND)
                        .any { it.contentEquals(operation.value) }
                operation.characteristicUuid.equals(
                    SonyBluetoothConstants.CHARACTERISTIC_UUID,
                    true
                ) ->
                registry.get(id)?.isLocationReady == true

                else -> true
            }
    })
    private val port = QueuedBleGattPort(queue, transport, registry)
    private val autoCorrection = CameraAutoCorrectionController(port, registry, scope)
    private val remoteControl: RemoteControlCoordinator = RemoteControlCoordinator(port, scope)
    private val sessionCoordinator = BleSessionCoordinator(port, remoteControl)

    val locationManager = LocationTransmissionManager(
        source = locationSource,
        readySessions = registry::readyIdentifiers,
        configFor = sessionCoordinator::getLocationDataConfig,
        port = port,
        scope = scope,
        isTransmissionAllowed = isTransmissionAllowed,
    )

    /**
     * SharedFlow, not a Channel: it supports several collectors (Android's app
     * graph and service both subscribe) and drops events while nobody collects
     * (a stopped Android service must not replay stale sounds on restart).
     * Subscribers must attach before the first connect — true for both shells,
     * which subscribe during construction on the main thread.
     */
    private val _events = MutableSharedFlow<OrchestratorEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<OrchestratorEvent> = _events

    override val sessions: StateFlow<Map<String, CameraSession>> get() = registry.sessions

    override fun refreshAutoCorrectionSettings(identifier: String) =
        autoCorrection.refresh(identifier)

    override fun setAutoCorrectionSetting(
        identifier: String,
        setting: CameraAutoCorrectionSetting,
        enabled: Boolean
    ) =
        autoCorrection.set(identifier, setting, enabled)

    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            transport.events.collect { event ->
                // Completion matching must run before any other routing so the
                // queue can release its lane for the next operation.
                val completedOperation = queue.onTransportEvent(event)
                handleTransportEvent(event, completedOperation)
            }
        }
        scope.launch {
            sessionCoordinator.events.collect { handleSessionEvent(it) }
        }
        scope.launch {
            locationManager.events.collect { event ->
                when (event) {
                    LocationEvent.FirstFixAcquired ->
                        _events.tryEmit(OrchestratorEvent.FirstLocationAcquired)

                    LocationEvent.NoLocationAvailable ->
                        _events.tryEmit(OrchestratorEvent.LocationUnavailable)
                }
            }
        }
    }

    // ---- Public API for platform shells ----

    /** A connection attempt was started by the shell. */
    fun onConnectRequested(identifier: String) {
        registry.upsert(identifier) { it.copy(phase = BleSessionPhase.Connecting) }
    }

    /** A connection attempt failed before any transport event (e.g. Bluetooth off). */
    fun onConnectFailed(identifier: String) {
        registry.updateIfPresent(identifier) { it.copy(phase = BleSessionPhase.Error) }
    }

    fun triggerRemoteShutter(identifier: String): Boolean =
        sendRemoteCommand(identifier, RemoteCommand.ShutterFullPress)

    suspend fun claimWifiControls(identifier: String) = remoteControl.claimWifiControls(identifier)

    internal suspend fun prepareCameraWifi(identifier: String): CameraWifiCredentials? {
        if (registry.get(identifier)?.phase != BleSessionPhase.Transmitting) {
            throw WifiRemoteConnectException(WifiRemoteFailure.BluetoothRequired)
        }
        return SonyWifiBootstrap(port).prepare(identifier)
    }
    fun releaseWifiControls(identifier: String) = remoteControl.releaseWifiControls(identifier)

    /**
     * Send a remote-control command. The coordinator gates on an active
     * connection and remote feature; the write is serialized via the queue.
     */
    fun sendRemoteCommand(identifier: String, command: RemoteCommand): Boolean =
        remoteControl.sendCommand(identifier, command)

    /**
     * Run one full shutter sequence (half press → focus delay → full press →
     * releases → wait for camera ready), as opposed to [triggerRemoteShutter]'s
     * single press with ack-driven release.
     */
    fun triggerShutterSequence(identifier: String): Boolean =
        remoteControl.startShutterSequence(identifier)

    fun setRemoteMonitoring(identifier: String, enabled: Boolean) {
        val id = identifier.uppercase()
        if (enabled) {
            remoteControl.startRemoteStatusMonitoring(id)
        } else {
            remoteControl.cancelProbe(id)
            port.setRemoteFeatureActive(id, false)
        }
    }

    fun clearDevice(identifier: String) {
        val id = identifier.uppercase()
        autoCorrection.clear(id)
        queue.cancelOperations(id, "session cleared")
        sessionCoordinator.clearSession(id)
        registry.markBleDisconnected(id)
        locationManager.updateTracking()
    }

    fun shutdownAll() {
        autoCorrection.clearAll()
        sessionCoordinator.clearAllSessions()
        queue.shutdown("shutdown")
        locationManager.shutdown()
        registry.clear()
    }

    // ---- Transport event routing ----

    private fun handleTransportEvent(event: BleTransportEvent, completedOperation: BleOperation?) {
        when (event) {
            is BleTransportEvent.Connected -> handleConnected(event.identifier)

            is BleTransportEvent.Disconnected -> {
                log.i { "Device ${event.identifier} disconnected (status=${event.statusCode})" }
                clearDevice(event.identifier)
                _events.tryEmit(OrchestratorEvent.DeviceDisconnected(event.identifier.uppercase()))
            }

            is BleTransportEvent.CharacteristicWritten -> {
                // These operations consume their own queue result, including failures.
                if (CameraAutoCorrectionSetting.fromUuid(event.characteristicUuid) != null) return
                // DD30 is shared by lock acquisition and release. A release
                // completion must not advance/restart the GPS-enable handshake.
                val isLocationLockRelease = completedOperation is BleOperation.Write &&
                        completedOperation.characteristicUuid.equals(
                            SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND, true,
                        ) && completedOperation.value.contentEquals(SonyBluetoothConstants.LOCATION_LOCK_RELEASE_COMMAND)
                if (!isLocationLockRelease) handleWritten(event)
            }

            is BleTransportEvent.CharacteristicRead -> {
                if (event.characteristicUuid.equals(
                        SonyBluetoothConstants.CHARACTERISTIC_READ_UUID,
                        true
                    )
                ) handleRead(
                    event
                )
            }

            is BleTransportEvent.SubscriptionChanged -> handleSubscriptionChanged(event)

            is BleTransportEvent.CharacteristicChanged -> handleCharacteristicChanged(event)

            is BleTransportEvent.ServicesDiscovered -> Unit // consumed by the queue
        }
    }

    private fun handleCharacteristicChanged(event: BleTransportEvent.CharacteristicChanged) {
        // The coordinator updates the camera-reported warning in the registry.
        // Neither status value releases the GPS lock or restarts the handshake.
        sessionCoordinator.onCharacteristicChanged(
            event.identifier, event.characteristicUuid, event.value,
        )
    }

    private fun handleConnected(identifier: String) {
        val id = identifier.uppercase()
        autoCorrection.clear(id)
        log.i { "Device $id connected" }
        registry.upsert(id) {
            it.copy(
                phase = BleSessionPhase.Connected,
                pairingRetryCount = 0,
                hasRetriedConfigRead = false,
                locationDisabledByCamera = false,
                autoTimeCorrection = CameraSettingState(),
                autoAreaAdjustment = CameraSettingState(),
            )
        }
        _events.tryEmit(OrchestratorEvent.DeviceConnected(id))

        scope.launch {
            val delayMs = runCatching { deviceDao.getHandshakeDelayMs(id) }.getOrNull() ?: 0L
            if (delayMs > 0) {
                // Some cameras stall their own boot while servicing BLE traffic;
                // the per-device delay lets them finish starting first
                log.i { "Delaying connection setup for $id by ${delayMs}ms" }
                delay(delayMs.milliseconds)
                if (registry.get(id) == null || !transport.isConnected(id)) return@launch
            }
            runDiscoveryAndHandshake(id)
        }
    }

    private suspend fun runDiscoveryAndHandshake(id: String) {
        registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.DiscoveringServices) }
        when (queue.execute(id, BleOperation.DiscoverServices)) {
            is BleOperationResult.Success -> sessionCoordinator.beginHandshake(id)
            is BleOperationResult.Cancelled -> Unit // disconnected meanwhile
            else -> {
                log.e { "Service discovery failed for $id" }
                registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.Error) }
            }
        }
    }

    private fun handleWritten(event: BleTransportEvent.CharacteristicWritten) {
        val id = event.identifier.uppercase()
        when (event.status) {
            BleOperationStatus.AuthError -> retryAfterAuthError(id) {
                // Restart the handshake after pairing settles (previous iOS behavior)
                sessionCoordinator.beginHandshake(id)
            }

            else -> {
                resetPairingRetries(id)
                sessionCoordinator.onCharacteristicWrite(
                    id,
                    event.characteristicUuid,
                    event.status == BleOperationStatus.Success,
                )
            }
        }
    }

    private fun handleRead(event: BleTransportEvent.CharacteristicRead) {
        val id = event.identifier.uppercase()
        when (event.status) {
            BleOperationStatus.AuthError -> retryAfterAuthError(id) {
                port.readCharacteristic(id, event.characteristicUuid)
            }

            BleOperationStatus.Failure -> {
                val isConfigRead = event.characteristicUuid.equals(
                    SonyBluetoothConstants.CHARACTERISTIC_READ_UUID,
                    ignoreCase = true,
                )
                val session = registry.get(id)
                if (isConfigRead && session != null && !session.hasRetriedConfigRead) {
                    // One-shot retry for the intermittent GATT 133 read failure
                    log.w { "Config read failed for $id, retrying once" }
                    registry.updateIfPresent(id) { it.copy(hasRetriedConfigRead = true) }
                    port.readCharacteristic(id, event.characteristicUuid)
                } else {
                    sessionCoordinator.onCharacteristicRead(id, event.value, false)
                }
            }

            BleOperationStatus.Success -> {
                resetPairingRetries(id)
                sessionCoordinator.onCharacteristicRead(id, event.value, true)
            }
        }
    }

    private fun handleSubscriptionChanged(event: BleTransportEvent.SubscriptionChanged) {
        val id = event.identifier.uppercase()
        when (event.status) {
            BleOperationStatus.AuthError -> retryAfterAuthError(id) {
                port.subscribeToNotifications(id, event.characteristicUuid)
            }

            BleOperationStatus.Failure ->
                log.w { "Subscription change failed for $id (${event.characteristicUuid})" }

            BleOperationStatus.Success -> resetPairingRetries(id)
        }
    }

    private fun retryAfterAuthError(identifier: String, retry: () -> Unit) {
        val session = registry.get(identifier) ?: return
        val attempts = session.pairingRetryCount + 1
        if (attempts > pairingPolicy.maxRetries) {
            log.e { "Pairing retries exhausted for $identifier" }
            _events.tryEmit(OrchestratorEvent.PairingFailed(identifier))
            return
        }
        log.w { "Auth error for $identifier, retry $attempts/${pairingPolicy.maxRetries}" }
        registry.updateIfPresent(identifier) { it.copy(pairingRetryCount = attempts) }
        scope.launch {
            val delayMs = if (attempts == 1) {
                pairingPolicy.firstRetryDelayMs
            } else {
                pairingPolicy.retryDelayMs
            }
            if (delayMs > 0) {
                delay(delayMs.milliseconds)
            }
            if (transport.isConnected(identifier)) {
                retry()
            }
        }
    }

    private fun resetPairingRetries(identifier: String) {
        val session = registry.get(identifier) ?: return
        if (session.pairingRetryCount != 0) {
            registry.updateIfPresent(identifier) { it.copy(pairingRetryCount = 0) }
        }
    }

    // ---- Coordinator event routing ----

    private suspend fun handleSessionEvent(event: BleSessionEvent) {
        when (event) {
            is BleSessionEvent.PhaseChanged ->
                registry.updateIfPresent(event.identifier) { it.copy(phase = event.phase) }

            is BleSessionEvent.HandshakeComplete -> handleHandshakeComplete(event.identifier)
        }
    }

    private suspend fun handleHandshakeComplete(identifier: String) {
        val id = identifier.uppercase()
        if (!shouldRemainConnected(id)) {
            log.i { "Device $id should not remain connected, skipping session start" }
            return
        }
        log.i { "Handshake complete for $id" }
        registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.Transmitting) }
        locationManager.onDeviceReady(id)
        autoCorrection.refresh(id)

        val remoteEnabled = runCatching { deviceDao.isRemoteControlEnabled(id) }
            .getOrDefault(false)
        if (remoteEnabled) {
            remoteControl.startRemoteStatusMonitoring(id)
        }
        _events.tryEmit(OrchestratorEvent.HandshakeCompleted(id))
    }
}
