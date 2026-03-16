package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.ensureInitialized
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
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
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.math.abs

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
    }

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
    // ------------------------------------------------------------------------

    private var latestLocation: CLLocation? = null
    private var transmissionJob: Job? = null
    private var locationUpdatesStarted = false

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
                when (characteristic.UUID.UUIDString.uppercase()) {
                    SonyBleConstants.LOCATION_CHARACTERISTIC_UUID_STRING -> session.locationWriteCharacteristic =
                        characteristic

                    SonyBleConstants.READ_CHARACTERISTIC_UUID_STRING -> session.readCharacteristic =
                        characteristic

                    SonyBleConstants.ENABLE_UNLOCK_GPS_UUID_STRING -> session.unlockGpsCharacteristic =
                        characteristic

                    SonyBleConstants.ENABLE_LOCK_GPS_UUID_STRING -> session.lockGpsCharacteristic =
                        characteristic

                    SonyBleConstants.TIME_SYNC_CHARACTERISTIC_UUID_STRING -> session.timeSyncCharacteristic =
                        characteristic
                }
            }

            when {
                session.locationConfig == null && session.readCharacteristic != null && session.phase == PeripheralPhase.Connected -> {
                    session.phase = PeripheralPhase.ReadingConfig
                    peripheral.readValueForCharacteristic(session.readCharacteristic!!)
                }

                session.locationConfig == null && session.readCharacteristic == null && session.phase == PeripheralPhase.Connected -> {
                    session.locationConfig =
                        LocationTransmissionConfig(shouldSendTimeZoneAndDst = false)
                    beginGpsEnable(session)
                }

                session.locationConfig != null && session.phase == PeripheralPhase.Connected -> {
                    beginGpsEnable(session)
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
            val value = didUpdateValueForCharacteristic.value?.toByteArray() ?: return

            if (didUpdateValueForCharacteristic.UUID.UUIDString.uppercase() == SonyBleConstants.READ_CHARACTERISTIC_UUID_STRING) {
                session.locationConfig = LocationTransmissionConfig(
                    shouldSendTimeZoneAndDst = hasTimeZoneDstFlag(value),
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

            when (didWriteValueForCharacteristic.UUID.UUIDString.uppercase()) {
                SonyBleConstants.ENABLE_UNLOCK_GPS_UUID_STRING -> {
                    val lockCharacteristic = session.lockGpsCharacteristic
                    if (lockCharacteristic != null) {
                        session.phase = PeripheralPhase.LockingGps
                        peripheral.writeValue(
                            data = SonyBleConstants.gpsEnableCommand.toNSData(),
                            forCharacteristic = lockCharacteristic,
                            type = CBCharacteristicWriteWithResponse,
                        )
                    } else {
                        beginTimeSyncOrTransmission(session)
                    }
                }

                SonyBleConstants.ENABLE_LOCK_GPS_UUID_STRING -> beginTimeSyncOrTransmission(session)
                SonyBleConstants.TIME_SYNC_CHARACTERISTIC_UUID_STRING -> markReadyForTransmission(
                    session
                )
            }
        }
    }

    private val locationDelegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            if (!shouldUpdateLocation(location)) return
            latestLocation = location
            sendLocationToReadyPeripherals(location)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            // Ignore location errors – a fix will arrive eventually.
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            updateLocationTracking()
        }
    }

    private val locationManager = CLLocationManager().apply {
        delegate = locationDelegate
        desiredAccuracy = platform.CoreLocation.kCLLocationAccuracyBest
        distanceFilter = platform.CoreLocation.kCLDistanceFilterNone
        pausesLocationUpdatesAutomatically = false
        allowsBackgroundLocationUpdates = true
    }

    // ---------------------------------------------------------------------------
    // CoreBluetooth delegate
    // ---------------------------------------------------------------------------
    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
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
                            SonyBleConstants.locationServiceUuid,
                            SonyBleConstants.controlServiceUuid,
                        )
                    )
                }
                // If not yet connected, CoreBluetooth still has the pending connection
                // request alive and will fire didConnectPeripheral when the device is found.
            }
            refreshDeviceList()
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
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
                    SonyBleConstants.locationServiceUuid,
                    SonyBleConstants.controlServiceUuid,
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
            if (id in autoReconnectIds) {
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
            if (id in autoReconnectIds) {
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
        if (central.state == CBManagerStatePoweredOn) {
            central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
        }
    }

    override suspend fun stopScan() {
        central.stopScan()
    }

    override suspend fun connect(identifier: String): Boolean {
        val peripheral = discovered[identifier] ?: return false
        if (connected.containsKey(identifier)) return true

        return suspendCancellableCoroutine { cont ->
            connectCallbacks[identifier] = { success ->
                if (cont.isActive) cont.resume(success)
            }
            central.connectPeripheral(peripheral, options = null)

            cont.invokeOnCancellation {
                connectCallbacks.remove(identifier)
                central.cancelPeripheralConnection(peripheral)
            }
        }
    }

    override suspend fun disconnect(identifier: String) {
        // Remove from persistence BEFORE cancelling so that the didDisconnect
        // callback does not immediately queue a reconnect.
        removePersistedPeripheral(identifier)

        val peripheral = connected[identifier] ?: discovered[identifier] ?: return

        suspendCancellableCoroutine<Unit> { cont ->
            disconnectCallbacks[identifier] = {
                if (cont.isActive) cont.resume(Unit)
            }
            central.cancelPeripheralConnection(peripheral)

            cont.invokeOnCancellation {
                disconnectCallbacks.remove(identifier)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun beginGpsEnable(session: PeripheralSession) {
        if (session.phase == PeripheralPhase.EnablingGps || session.phase == PeripheralPhase.LockingGps || session.phase == PeripheralPhase.SyncingTime || session.phase == PeripheralPhase.Ready) {
            return
        }

        val unlockCharacteristic = session.unlockGpsCharacteristic
        if (unlockCharacteristic != null) {
            session.phase = PeripheralPhase.EnablingGps
            session.peripheral.writeValue(
                data = SonyBleConstants.gpsEnableCommand.toNSData(),
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
                data = buildTimeSyncPacket().toNSData(),
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
        latestLocation?.let { sendLocationToPeripheral(session, it) }
    }

    private fun updateLocationTracking() {
        val hasReadyPeripheral = sessions.values.any { it.phase == PeripheralPhase.Ready }
        if (!hasReadyPeripheral) {
            if (locationUpdatesStarted) {
                locationManager.stopUpdatingLocation()
                locationUpdatesStarted = false
            }
            transmissionJob?.cancel()
            transmissionJob = null
            return
        }

        if (!locationUpdatesStarted) {
            locationManager.requestAlwaysAuthorization()
            locationManager.startUpdatingLocation()
            // Prime the first fix quickly so transmission can start without waiting for the next interval.
            locationManager.requestLocation()
            locationUpdatesStarted = true
        }

        if (transmissionJob == null) {
            transmissionJob = controllerScope.launch {
                while (isActive) {
                    delay(SonyBleConstants.locationUpdateIntervalMs)
                    latestLocation?.let { sendLocationToReadyPeripherals(it) }
                }
            }
        }
    }

    private fun sendLocationToReadyPeripherals(location: CLLocation) {
        sessions.values
            .filter { it.phase == PeripheralPhase.Ready }
            .forEach { sendLocationToPeripheral(it, location) }
    }

    private fun sendLocationToPeripheral(session: PeripheralSession, location: CLLocation) {
        val characteristic = session.locationWriteCharacteristic ?: return
        val config = session.locationConfig ?: LocationTransmissionConfig(false)
        session.peripheral.writeValue(
            data = buildLocationDataPacket(config, location).toNSData(),
            forCharacteristic = characteristic,
            type = CBCharacteristicWriteWithResponse,
        )
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
                central.connectPeripheral(peripheral, options = null)
            }
        }
        refreshDeviceList()
    }

    private fun refreshDeviceList() {
        _devices.update {
            discovered.map { (id, peripheral) ->
                BluetoothDeviceInfo(
                    identifier = id,
                    name = peripheral.name ?: "Unknown Device",
                    isConnected = connected.containsKey(id),
                )
            }
        }
    }

    private fun shouldUpdateLocation(newLocation: CLLocation): Boolean {
        val current = latestLocation ?: return true

        // Unknown accuracy values should not block updates.
        if (newLocation.horizontalAccuracy < 0 || current.horizontalAccuracy < 0) {
            return true
        }

        val accuracyDifference = newLocation.horizontalAccuracy - current.horizontalAccuracy
        if (accuracyDifference <= SonyBleConstants.accuracyThresholdMeters) {
            return true
        }

        val ageMs =
            (newLocation.timestamp.timeIntervalSince1970 - current.timestamp.timeIntervalSince1970) * 1000.0
        return ageMs > SonyBleConstants.oldLocationThresholdMs
    }
}

private enum class PeripheralPhase {
    Connected,
    ReadingConfig,
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
    var locationConfig: LocationTransmissionConfig? = null,
    var phase: PeripheralPhase = PeripheralPhase.Connected,
)

private data class LocationTransmissionConfig(
    val shouldSendTimeZoneAndDst: Boolean,
) {
    val dataSize: Int = if (shouldSendTimeZoneAndDst) 95 else 91

    val fixedBytes: ByteArray = byteArrayOf(
        0x00,
        if (shouldSendTimeZoneAndDst) 0x5D else 0x59,
        0x08,
        0x02,
        0xFC.toByte(),
        if (shouldSendTimeZoneAndDst) 0x03 else 0x00,
        0x00,
        0x00,
        0x10,
        0x10,
        0x10,
    )
}

private object SonyBleConstants {
    const val LOCATION_SERVICE_UUID_STRING = "8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF"
    const val CONTROL_SERVICE_UUID_STRING = "8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF"
    const val LOCATION_CHARACTERISTIC_UUID_STRING = "0000DD11-0000-1000-8000-00805F9B34FB"
    const val READ_CHARACTERISTIC_UUID_STRING = "0000DD21-0000-1000-8000-00805F9B34FB"
    const val ENABLE_UNLOCK_GPS_UUID_STRING = "0000DD30-0000-1000-8000-00805F9B34FB"
    const val ENABLE_LOCK_GPS_UUID_STRING = "0000DD31-0000-1000-8000-00805F9B34FB"
    const val TIME_SYNC_CHARACTERISTIC_UUID_STRING = "0000CC13-0000-1000-8000-00805F9B34FB"

    val locationServiceUuid: CBUUID = CBUUID.UUIDWithString(LOCATION_SERVICE_UUID_STRING)
    val controlServiceUuid: CBUUID = CBUUID.UUIDWithString(CONTROL_SERVICE_UUID_STRING)
    val gpsEnableCommand = byteArrayOf(0x01)
    const val locationUpdateIntervalMs = 10_000L
    const val accuracyThresholdMeters = 200.0
    const val oldLocationThresholdMs = 5 * 60 * 1000.0
}

private fun hasTimeZoneDstFlag(value: ByteArray): Boolean {
    return value.size >= 5 && (value[4].toInt() and 0x02) != 0
}

private fun buildLocationDataPacket(
    config: LocationTransmissionConfig,
    location: CLLocation,
): ByteArray {
    val locationBytes = convertCoordinates(location)
    val dateBytes = convertUtcDate()
    val timeZoneOffsetBytes = convertTimeZoneOffset()
    val dstOffsetBytes = convertDstOffset()
    val paddingBytes = ByteArray(65)

    val data = ByteArray(config.dataSize)
    var currentPosition = 0

    config.fixedBytes.copyInto(data, destinationOffset = currentPosition)
    currentPosition += config.fixedBytes.size

    locationBytes.copyInto(data, destinationOffset = currentPosition)
    currentPosition += locationBytes.size

    dateBytes.copyInto(data, destinationOffset = currentPosition)
    currentPosition += dateBytes.size

    paddingBytes.copyInto(data, destinationOffset = currentPosition)

    if (config.shouldSendTimeZoneAndDst) {
        currentPosition += paddingBytes.size
        timeZoneOffsetBytes.copyInto(data, destinationOffset = currentPosition)
        currentPosition += timeZoneOffsetBytes.size
        dstOffsetBytes.copyInto(data, destinationOffset = currentPosition)
    }

    return data
}

@OptIn(ExperimentalForeignApi::class)
private fun convertCoordinates(location: CLLocation): ByteArray {
    val latitude = (location.coordinate.useContents { latitude } * 1.0E7).toInt()
    val longitude = (location.coordinate.useContents { longitude } * 1.0E7).toInt()
    return latitude.toByteArray() + longitude.toByteArray()
}

private fun convertUtcDate(): ByteArray {
    val now = platform.Foundation.NSDate()
    val calendar = NSCalendar.currentCalendar
    val components = calendar.components(
        unitFlags = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond,
        fromDate = now,
    )

    val yearBytes = components.year.toShort().toByteArray()
    return byteArrayOf(
        yearBytes[0],
        yearBytes[1],
        components.month.toByte(),
        components.day.toByte(),
        components.hour.toByte(),
        components.minute.toByte(),
        components.second.toByte(),
    )
}

private fun convertTimeZoneOffset(): ByteArray {
    val now = platform.Foundation.NSDate()
    val timeZone = NSCalendar.currentCalendar.timeZone
    val currentOffsetMinutes = timeZone.secondsFromGMTForDate(now).toInt() / 60
    val dstOffsetMinutes = (timeZone.daylightSavingTimeOffsetForDate(now) / 60.0).toInt()
    val standardOffsetMinutes = currentOffsetMinutes - dstOffsetMinutes
    return standardOffsetMinutes.toShort().toByteArray()
}

private fun convertDstOffset(): ByteArray {
    val now = platform.Foundation.NSDate()
    val dstMinutes =
        (NSCalendar.currentCalendar.timeZone.daylightSavingTimeOffsetForDate(now) / 60.0).toInt()
    return dstMinutes.toShort().toByteArray()
}

private fun buildTimeSyncPacket(): ByteArray {
    val now = platform.Foundation.NSDate()
    val calendar = NSCalendar.currentCalendar
    val components = calendar.components(
        unitFlags = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond,
        fromDate = now,
    )

    val timeZone = NSCalendar.currentCalendar.timeZone
    val totalOffsetMinutes = timeZone.secondsFromGMTForDate(now).toInt() / 60
    val dstMinutes = (timeZone.daylightSavingTimeOffsetForDate(now) / 60.0).toInt()
    val standardOffsetMinutes = totalOffsetMinutes - dstMinutes
    val hoursComponent = abs(standardOffsetMinutes / 60)
    val offsetMinutesComponent = abs(standardOffsetMinutes % 60)
    val signedOffsetHourByte =
        (if (standardOffsetMinutes < 0) -hoursComponent else hoursComponent).toByte()
    val yearBytes = components.year.toShort().toByteArray()

    return ByteArray(13).apply {
        this[0] = 12
        this[1] = 0
        this[2] = 0
        this[3] = yearBytes[0]
        this[4] = yearBytes[1]
        this[5] = components.month.toByte()
        this[6] = components.day.toByte()
        this[7] = components.hour.toByte()
        this[8] = components.minute.toByte()
        this[9] = components.second.toByte()
        this[10] = if (dstMinutes > 0) 1 else 0
        this[11] = signedOffsetHourByte
        this[12] = offsetMinutesComponent.toByte()
    }
}

private fun Int.toByteArray(): ByteArray = byteArrayOf(
    (this shr 24).toByte(),
    (this shr 16).toByte(),
    (this shr 8).toByte(),
    this.toByte(),
)

private fun Short.toByteArray(): ByteArray = byteArrayOf(
    (toInt() shr 8).toByte(),
    toByte(),
)

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
