package com.sasch.cameragps.sharednew.bluetooth.coordinator

import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.PlatformLogger
import com.sasch.cameragps.sharednew.remote.BulbPhase
import com.sasch.cameragps.sharednew.remote.BulbTimerState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlCoordinatorTest {

    @BeforeTest
    fun disablePlatformLogging() {
        KmLogging.setLoggers()
    }

    @AfterTest
    fun restorePlatformLogging() {
        KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true)))
    }

    // ---- Pure function tests ----

    @Test
    fun isRemoteFeatureActive_emptyArray_returnsFalse() {
        assertFalse(RemoteControlCoordinator.isRemoteFeatureActive(byteArrayOf()))
    }

    @Test
    fun isRemoteFeatureActive_inactiveValue_returnsFalse() {
        val inactive = byteArrayOf(0x02, 0xC3.toByte(), 0x00)
        assertFalse(RemoteControlCoordinator.isRemoteFeatureActive(inactive))
    }

    @Test
    fun isRemoteFeatureActive_activeValue_returnsTrue() {
        val active = byteArrayOf(0x02, 0xA0.toByte(), 0x00)
        assertTrue(RemoteControlCoordinator.isRemoteFeatureActive(active))
    }

    @Test
    fun shouldSendShutterUp_matchingValue_returnsTrue() {
        val value = byteArrayOf(0x02, 0xA0.toByte(), 0x00)
        assertTrue(RemoteControlCoordinator.shouldSendShutterUp(value))
    }

    @Test
    fun shouldSendShutterUp_nonMatchingValue_returnsFalse() {
        val value = byteArrayOf(0x02, 0xC3.toByte(), 0x00)
        assertFalse(RemoteControlCoordinator.shouldSendShutterUp(value))
    }

    @Test
    fun hasTimeZoneDstFlag_shortArray_returnsFalse() {
        assertFalse(RemoteControlCoordinator.hasTimeZoneDstFlag(byteArrayOf(0, 1, 2, 3)))
    }

    @Test
    fun hasTimeZoneDstFlag_flagSet_returnsTrue() {
        val value = byteArrayOf(0, 0, 0, 0, 0x02)
        assertTrue(RemoteControlCoordinator.hasTimeZoneDstFlag(value))
    }

    @Test
    fun hasTimeZoneDstFlag_flagNotSet_returnsFalse() {
        val value = byteArrayOf(0, 0, 0, 0, 0x01)
        assertFalse(RemoteControlCoordinator.hasTimeZoneDstFlag(value))
    }

    // ---- Coordinator integration tests with fake port ----

    @Test
    fun wifiOwnershipStopsBleProbesAndPressesUntilReleased() = runTest {
        val id = "AA:BB:CC:DD:EE:FF"
        val port = FakeBleGattPort()
        port.connectedDevices.add(id)
        port.devicesWithRemoteControl.add(id)
        port.remoteActiveDevices.add(id)
        val coordinator = RemoteControlCoordinator(port, backgroundScope)
        coordinator.startRemoteStatusMonitoring(id)
        coordinator.claimWifiControls(id)
        val writes = port.writtenCharacteristics.size
        assertFalse(coordinator.sendCommand(id, RemoteCommand.ShutterFullPress))
        assertFalse(coordinator.startShutterSequence(id))
        assertFalse(coordinator.onRemoteStatusChanged(id, byteArrayOf(2, 0xa0.toByte(), 0)))
        coordinator.startRemoteStatusMonitoring(id)
        advanceTimeBy(10_000)
        assertEquals(writes, port.writtenCharacteristics.size)
        coordinator.releaseWifiControls(id)
        coordinator.onRemoteStatusChanged(id, byteArrayOf(2, 0xa0.toByte(), 0))
        assertTrue(coordinator.sendCommand(id, RemoteCommand.ShutterFullPress))
    }

    @Test
    fun handleRemoteShutterRequest_notConnected_returnsFalse() = runTest {
        val port = FakeBleGattPort()
        val coordinator = RemoteControlCoordinator(port, backgroundScope)

        assertFalse(coordinator.handleRemoteShutterRequest("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun handleRemoteShutterRequest_connectedButRemoteInactive_returnsFalse() = runTest {
        val port = FakeBleGattPort()
        port.connectedDevices.add("AA:BB:CC:DD:EE:FF")
        port.devicesWithRemoteControl.add("AA:BB:CC:DD:EE:FF")
        val coordinator = RemoteControlCoordinator(port, backgroundScope)

        assertFalse(coordinator.handleRemoteShutterRequest("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun handleRemoteShutterRequest_connectedAndActive_returnsTrue() = runTest {
        val port = FakeBleGattPort()
        port.connectedDevices.add("AA:BB:CC:DD:EE:FF")
        port.devicesWithRemoteControl.add("AA:BB:CC:DD:EE:FF")
        port.remoteActiveDevices.add("AA:BB:CC:DD:EE:FF")
        val coordinator = RemoteControlCoordinator(port, backgroundScope)

        assertTrue(coordinator.handleRemoteShutterRequest("AA:BB:CC:DD:EE:FF"))
        assertEquals(1, port.writtenCharacteristics.size)
    }

    @Test
    fun onRemoteStatusChanged_activeValue_stopsProbeLoop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)

        val port = FakeBleGattPort()
        port.connectedDevices.add("AA:BB:CC:DD:EE:FF")
        port.devicesWithRemoteControl.add("AA:BB:CC:DD:EE:FF")

        val coordinator = RemoteControlCoordinator(port, scope.backgroundScope)
        coordinator.startRemoteStatusMonitoring("AA:BB:CC:DD:EE:FF")

        // Advance past initial delay + one probe
        scope.advanceTimeBy(4_000)
        val probeCountBefore = port.writtenCharacteristics.size

        // Report feature as active → should stop probing
        val active = byteArrayOf(0x02, 0xA0.toByte(), 0x00)
        coordinator.onRemoteStatusChanged("AA:BB:CC:DD:EE:FF", active)

        // Advance more time — no new probes should appear
        scope.advanceTimeBy(10_000)
        assertEquals(probeCountBefore, port.writtenCharacteristics.size)
    }

    @Test
    fun bulbUsesTwoClicksAndBlocksOtherShutterActions() = runTest {
        val port = FakeBleGattPort().apply {
            connectedDevices.add("CAMERA")
            remoteActiveDevices.add("CAMERA")
        }
        val coordinator = RemoteControlCoordinator(port, backgroundScope)
        assertTrue(coordinator.startBulbTimer("camera", 5))
        runCurrent()
        assertFalse(coordinator.startBulbTimer("camera", 5))
        assertFalse(coordinator.startShutterSequence("camera"))
        assertFalse(coordinator.sendCommand("camera", RemoteCommand.ShutterFullPress))
        assertFalse(coordinator.onRemoteStatusChanged("camera", byteArrayOf(2, 0xa0.toByte(), 0)))
        assertEquals(2, port.writtenCharacteristics.size)
        advanceTimeBy(5000)
        coordinator.stopBulbTimer("camera")
        coordinator.stopBulbTimer("camera")
        runCurrent()
        assertEquals(
            listOf("0109", "0108", "0109", "0108"),
            port.writtenCharacteristics.map {
                it.third.joinToString("") { byte ->
                    byte.toUByte().toString(16).padStart(2, '0')
                }
            })
        assertEquals(BulbPhase.Finished, port.bulbState.phase)
    }

    @Test
    fun uncertainBulbStartOnlyReleasesAndNeverRetriesToggle() = runTest {
        val port = FakeBleGattPort().apply {
            connectedDevices.add("CAMERA")
            remoteActiveDevices.add("CAMERA")
            awaitResults.add(false)
        }
        val coordinator = RemoteControlCoordinator(port, backgroundScope)
        coordinator.startBulbTimer("camera", 5)
        runCurrent()
        assertEquals(2, port.writtenCharacteristics.size)
        assertEquals(BulbPhase.Uncertain, port.bulbState.phase)
    }

    @Test
    fun bulbDisconnectDoesNotSendAnotherToggle() = runTest {
        val port = FakeBleGattPort().apply {
            connectedDevices.add("CAMERA")
            remoteActiveDevices.add("CAMERA")
        }
        val coordinator = RemoteControlCoordinator(port, backgroundScope)
        coordinator.startBulbTimer("camera", 5)
        runCurrent()
        port.connectedDevices.clear()
        coordinator.cancelProbe("camera")
        runCurrent()
        advanceTimeBy(6000)
        assertEquals(2, port.writtenCharacteristics.size)
        assertEquals(BulbPhase.Uncertain, port.bulbState.phase)
    }

    @Test
    fun identifiers_are_uppercased() = runTest {
        val port = FakeBleGattPort()
        port.connectedDevices.add("AA:BB:CC:DD:EE:FF")
        port.devicesWithRemoteControl.add("AA:BB:CC:DD:EE:FF")
        port.remoteActiveDevices.add("AA:BB:CC:DD:EE:FF")
        val coordinator = RemoteControlCoordinator(port, backgroundScope)

        // Call with lowercase — should still work
        assertTrue(coordinator.handleRemoteShutterRequest("aa:bb:cc:dd:ee:ff"))
    }
}

/**
 * Minimal fake for testing the shared coordinator without real BLE.
 */
private class FakeBleGattPort : BleGattPort {
    val awaitResults = ArrayDeque<Boolean>()
    var bulbState = BulbTimerState()
    override suspend fun writeCharacteristicAwait(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray
    ): Boolean {
        writeCharacteristic(identifier, characteristicUuid, value)
        return awaitResults.removeFirstOrNull() ?: true
    }

    override fun setBulbTimerState(identifier: String, state: BulbTimerState) {
        bulbState = state
    }

    override fun setAutoCorrectionState(
        identifier: String,
        setting: com.sasch.cameragps.sharednew.bluetooth.session.CameraAutoCorrectionSetting,
        state: com.sasch.cameragps.sharednew.bluetooth.session.CameraSettingState,
    ) = Unit
    val connectedDevices = mutableSetOf<String>()
    val devicesWithRemoteControl = mutableSetOf<String>()
    val remoteActiveDevices = mutableSetOf<String>()
    val shutterActiveDevices = mutableSetOf<String>()
    val writtenCharacteristics = mutableListOf<Triple<String, String, ByteArray>>()
    val subscriptions = mutableListOf<Pair<String, String>>()

    override fun writeCharacteristic(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): Boolean {
        writtenCharacteristics.add(Triple(identifier, characteristicUuid, value))
        return true
    }

    override fun subscribeToNotifications(
        identifier: String,
        characteristicUuid: String,
    ): Boolean {
        subscriptions.add(identifier to characteristicUuid)
        return true
    }

    override fun isConnected(identifier: String): Boolean =
        identifier in connectedDevices

    override fun hasRemoteControlCharacteristic(identifier: String): Boolean =
        identifier in devicesWithRemoteControl

    override fun isRemoteFeatureActive(identifier: String): Boolean =
        identifier in remoteActiveDevices

    override fun setRemoteFeatureActive(identifier: String, active: Boolean) {
        if (active) remoteActiveDevices.add(identifier) else remoteActiveDevices.remove(identifier)
    }

    override fun setShutterSequenceActive(identifier: String, active: Boolean) {
        if (active) shutterActiveDevices.add(identifier) else shutterActiveDevices.remove(identifier)
    }

    override fun readCharacteristic(identifier: String, characteristicUuid: String): Boolean = true

    override fun setLocationDisabledByCamera(identifier: String, disabled: Boolean) = Unit
    override fun isLocationDisabledByCamera(identifier: String) = false

    override fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean =
        identifier in devicesWithRemoteControl
}
