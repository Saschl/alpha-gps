package com.sasch.cameragps.sharednew.bluetooth.session

import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.PlatformLogger
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants as Sony

@OptIn(ExperimentalCoroutinesApi::class)
class CameraLocationLinkingTest {
    @BeforeTest
    fun disablePlatformLogging() {
        KmLogging.setLoggers()
    }

    @AfterTest
    fun restorePlatformLogging() {
        KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true)))
    }

    @Test
    fun subscribesBeforeTheUnchangedHandshakeAndSupportsCamerasWithoutDd01() = runTest {
        for (hasStatus in listOf(true, false)) {
            val f = Fixture(backgroundScope)
            f.transport.hasLocationStatus = hasStatus
            f.connect("A")
            runCurrent()
            val setup = listOf(
                BleOperation.Read(Sony.CHARACTERISTIC_READ_UUID),
                BleOperation.Write(
                    Sony.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND,
                    Sony.GPS_ENABLE_COMMAND
                ),
                BleOperation.Write(
                    Sony.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND,
                    Sony.GPS_ENABLE_COMMAND
                ),
            )
            val expected = if (hasStatus) {
                listOf(
                    BleOperation.Subscribe(
                        Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA,
                        true
                    )
                ) + setup
            } else setup
            assertEquals(expected, f.transport.operations.map { it.second }.drop(1).dropLast(1))
            assertTrue(f.session("A").isLocationReady)
            f.orchestrator.shutdownAll()
        }
    }

    @Test
    fun disableStopsGpsAndCachedSendsButKeepsTheConnectionAndShutter() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("A")
        runCurrent()
        f.fix()
        f.notify("A", Sony.REMOTE_STATUS_UUID, byteArrayOf(2, 0xA0.toByte(), 0))
        runCurrent()
        assertTrue(f.orchestrator.locationManager.isTransmitting.value)
        f.notify(
            "a",
            Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA.uppercase(),
            byteArrayOf(3, 1, 2, 0)
        )
        runCurrent()
        assertTrue(f.session("A").locationDisabledByCamera)
        assertTrue(f.session("A").remoteFeatureActive)
        assertTrue(f.transport.isConnected("A"))
        assertFalse(f.orchestrator.locationManager.isActive.value)
        assertFalse(f.orchestrator.locationManager.isTransmitting.value)
        assertTrue(f.transport.operations.any { (_, op) ->
            op is BleOperation.Write && op.characteristicUuid == Sony.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND &&
                    op.value.contentEquals(byteArrayOf(0))
        })
        val sent = f.transport.locationWrites().size
        // A late handshake completion must not send the cached fix to a disabled camera.
        f.orchestrator.locationManager.onDeviceReady("A")
        advanceTimeBy(Sony.LOCATION_UPDATE_INTERVAL_MS * 2)
        runCurrent()
        assertEquals(sent, f.transport.locationWrites().size)
        assertFalse(f.source.active)
        assertTrue(f.orchestrator.triggerRemoteShutter("A"))
        runCurrent()
        assertTrue(f.transport.operations.any { (_, op) ->
            op is BleOperation.Write && op.characteristicUuid == Sony.REMOTE_CHARACTERISTIC_UUID &&
                    op.value.contentEquals(Sony.FULL_SHUTTER_DOWN_COMMAND)
        })
    }

    @Test
    fun disablingOneCameraDoesNotStopTheOtherCamera() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("A")
        f.connect("B")
        runCurrent()
        f.fix()
        runCurrent()
        f.notify("A", Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA, byteArrayOf(3, 1, 2, 0))
        runCurrent()
        assertTrue(f.source.active)
        assertEquals(setOf("B"), f.orchestrator.registry.readyIdentifiers())
        f.transport.operations.clear()
        advanceTimeBy(Sony.LOCATION_UPDATE_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("B"), f.transport.locationWrites().map { it.first })
    }

    @Test
    fun disableDuringHandshakeSurvivesCompletionAndAReconnectClearsIt() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.holdReads = true
        f.connect("A")
        runCurrent()
        f.notify("A", Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA, byteArrayOf(3, 1, 2, 0))
        runCurrent()
        f.transport.completeRead("A")
        runCurrent()
        assertEquals(BleSessionPhase.Transmitting, f.session("A").phase)
        assertTrue(f.session("A").locationDisabledByCamera)
        assertFalse(f.source.active)
        f.transport.connected.remove("A")
        f.transport.emit(BleTransportEvent.Disconnected("A", null))
        runCurrent()
        f.transport.holdReads = false
        f.connect("A")
        runCurrent()
        assertFalse(f.session("A").locationDisabledByCamera)
        assertTrue(f.session("A").isLocationReady)
        assertTrue(f.source.active)
    }

    @Test
    fun availableNotificationResumesOnlyAfterHandshakeAndDuplicatesAreHarmless() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("A")
        runCurrent()
        f.fix()
        runCurrent()
        f.notify("A", Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA, byteArrayOf(3, 1, 2, 0))
        runCurrent()
        f.transport.operations.clear()
        f.transport.holdReads = true
        f.notify("A", Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA, byteArrayOf(3, 1, 3, 1))
        runCurrent()
        assertFalse(f.session("A").isLocationReady)
        assertFalse(f.source.active)
        assertTrue(f.transport.locationWrites().isEmpty())
        f.transport.completeRead("A")
        runCurrent()
        assertTrue(f.session("A").isLocationReady)
        assertTrue(f.source.active)
        assertEquals(1, f.transport.locationWrites().size)
        val reads = f.transport.operations.count { it.second is BleOperation.Read }
        f.notify("A", Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA, byteArrayOf(3, 1, 3, 1))
        runCurrent()
        assertEquals(reads, f.transport.operations.count { it.second is BleOperation.Read })
    }

    @Test
    fun ignoresUnknownPayloadsOtherCharacteristicsAndNotificationsAfterDisconnect() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("A")
        runCurrent()
        for (payload in listOf(
            byteArrayOf(),
            byteArrayOf(3, 1, 2),
            byteArrayOf(3, 1, 2, 1),
            byteArrayOf(3, 1, 2, 0, 0)
        )) {
            f.notify("A", Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA, payload)
        }
        f.notify("A", Sony.CHARACTERISTIC_READ_UUID, byteArrayOf(3, 1, 2, 0))
        runCurrent()
        assertFalse(f.session("A").locationDisabledByCamera)
        f.transport.connected.remove("A")
        f.transport.emit(BleTransportEvent.Disconnected("A", null))
        f.notify("A", Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA, byteArrayOf(3, 1, 2, 0))
        runCurrent()
        assertTrue(f.orchestrator.sessions.value.isEmpty())
    }

    @Test
    fun dropsParkedLocationPacketsWithoutCancellingParkedRemoteCommands() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("A")
        runCurrent()
        f.transport.holdLocationWrites = true
        f.fix()
        runCurrent()
        assertEquals(1, f.transport.locationWrites().size) // in flight, cannot recall
        advanceTimeBy(Sony.LOCATION_UPDATE_INTERVAL_MS)
        runCurrent() // second location packet is now parked behind the first
        f.notify("A", Sony.REMOTE_STATUS_UUID, byteArrayOf(2, 1, 1))
        runCurrent()
        assertTrue(f.orchestrator.triggerRemoteShutter("A"))
        f.notify("A", Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA, byteArrayOf(3, 1, 2, 0))
        runCurrent()
        f.transport.completeWrite("A", Sony.CHARACTERISTIC_UUID)
        runCurrent()
        assertEquals(1, f.transport.locationWrites().size)
        assertTrue(f.transport.operations.any { (_, op) ->
            op is BleOperation.Write && op.characteristicUuid == Sony.REMOTE_CHARACTERISTIC_UUID
        })
    }

    private class Fixture(scope: CoroutineScope) {
        val source = FakeSource()
        val transport = FakeTransport()
        val orchestrator =
            CameraSessionOrchestrator(transport, source, FakeDao(), scope).also { it.start() }

        fun connect(id: String) {
            transport.connected.add(id)
            transport.emit(BleTransportEvent.Connected(id))
        }

        fun session(id: String) = orchestrator.registry.get(id)!!
        fun notify(id: String, uuid: String, bytes: ByteArray) =
            transport.emit(BleTransportEvent.CharacteristicChanged(id, uuid, bytes))

        fun fix() {
            source.channel.trySend(
                GeoLocation(
                    52.52,
                    13.405,
                    5.0,
                    Clock.System.now().toEpochMilliseconds()
                )
            )
        }
    }

    private class FakeSource : LocationSource {
        val channel = Channel<GeoLocation>(Channel.UNLIMITED)
        override val locations = channel.receiveAsFlow()
        var active = false
        override fun start(): Boolean {
            active = true; return true
        }

        override fun stop() {
            active = false
        }

        override fun hasPreciseAuthorization() = true
    }

    private class FakeTransport : BlePeripheralTransport {
        val channel = Channel<BleTransportEvent>(Channel.UNLIMITED)
        override val events = channel.receiveAsFlow()
        val connected = mutableSetOf<String>()
        val operations = mutableListOf<Pair<String, BleOperation>>()
        var hasLocationStatus = true
        var holdReads = false
        var holdLocationWrites = false
        fun emit(event: BleTransportEvent) {
            channel.trySend(event)
        }

        fun locationWrites() = operations.filter { (_, op) ->
            op is BleOperation.Write && op.characteristicUuid == Sony.CHARACTERISTIC_UUID
        }

        override fun isConnected(identifier: String) = identifier in connected
        override fun hasCharacteristic(identifier: String, characteristicUuid: String) =
            characteristicUuid != Sony.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA || hasLocationStatus

        override fun initiateDiscoverServices(identifier: String): Boolean {
            operations += identifier to BleOperation.DiscoverServices
            emit(BleTransportEvent.ServicesDiscovered(identifier, true))
            return true
        }

        override fun initiateRead(identifier: String, characteristicUuid: String): Boolean {
            operations += identifier to BleOperation.Read(characteristicUuid)
            if (!holdReads) completeRead(identifier)
            return true
        }

        fun completeRead(id: String) = emit(
            BleTransportEvent.CharacteristicRead(
                id,
                Sony.CHARACTERISTIC_READ_UUID,
                byteArrayOf(0, 0, 0, 0, 2),
                BleOperationStatus.Success,
            )
        )

        override fun initiateWrite(
            identifier: String,
            characteristicUuid: String,
            value: ByteArray
        ): Boolean {
            operations += identifier to BleOperation.Write(characteristicUuid, value)
            if (!holdLocationWrites || characteristicUuid != Sony.CHARACTERISTIC_UUID) completeWrite(
                identifier,
                characteristicUuid
            )
            return true
        }

        fun completeWrite(id: String, uuid: String) =
            emit(BleTransportEvent.CharacteristicWritten(id, uuid, BleOperationStatus.Success))

        override fun initiateSubscribe(
            identifier: String,
            characteristicUuid: String,
            enable: Boolean
        ): Boolean {
            operations += identifier to BleOperation.Subscribe(characteristicUuid, enable)
            emit(
                BleTransportEvent.SubscriptionChanged(
                    identifier,
                    characteristicUuid,
                    enable,
                    BleOperationStatus.Success
                )
            )
            return true
        }
    }

    private class FakeDao : CameraDeviceDAO {
        override suspend fun getAllCameraDevices() = emptyList<CameraDevice>()
        override fun observeAllDevices() = flowOf(emptyList<CameraDevice>())
        override suspend fun insertDevice(device: CameraDevice) = Unit
        override suspend fun setDeviceName(deviceId: String, name: String, isCustom: Boolean) = Unit
        override suspend fun getDeviceName(address: String): String? = null
        override suspend fun deleteDevice(device: CameraDevice) = Unit
        override suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean) = Unit
        override suspend fun isDeviceAlwaysOnEnabled(address: String) = false
        override suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean) = Unit
        override suspend fun isDeviceEnabled(address: String) = true
        override suspend fun findDeviceEnabled(address: String): Boolean? = true
        override suspend fun getAlwaysOnEnabledDeviceCount() = 0
        override suspend fun setRemoteControlEnabled(deviceId: String, enabled: Boolean) = 0
        override suspend fun isRemoteControlEnabled(address: String) = false
        override suspend fun getHandshakeDelayMs(address: String): Long? = 0
        override suspend fun setHandshakeDelayMs(deviceId: String, delayMs: Long) = Unit
    }
}
