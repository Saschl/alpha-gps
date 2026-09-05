package com.sasch.cameragps.sharednew.bluetooth.location

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleGattPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class LocationTransmissionStateTest {
    @Test
    fun requiresQueuedLocationAndClearsOnDisconnectOrAppDisable() = runTest {
        val f = Fixture(backgroundScope)
        f.manager.onDeviceReady("A")
        runCurrent()
        assertTrue(f.manager.isActive.value)
        assertFalse(f.manager.isTransmitting.value)
        f.source.locations.emit(fix())
        runCurrent()
        assertTrue(f.manager.isTransmitting.value)
        f.allowed = false
        f.manager.updateTracking()
        assertFalse(f.manager.isTransmitting.value)
        f.allowed = true
        f.manager.onDeviceReady("A")
        runCurrent()
        f.source.locations.emit(fix())
        runCurrent()
        assertTrue(f.manager.isTransmitting.value)
        f.ready.clear()
        f.manager.updateTracking()
        assertFalse(f.manager.isTransmitting.value)
    }

    @Test
    fun rejectedWritesAndUnavailableLocationSourceDoNotReportTransmission() = runTest {
        val f = Fixture(backgroundScope)
        f.port.acceptWrites = false
        f.manager.onDeviceReady("A")
        runCurrent()
        f.source.locations.emit(fix())
        runCurrent()
        assertFalse(f.manager.isTransmitting.value)
        f.port.acceptWrites = true
        advanceTimeBy(SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS)
        runCurrent()
        assertTrue(f.manager.isTransmitting.value)
        f.manager.shutdown()
        assertFalse(f.manager.isTransmitting.value)
        f.source.canStart = false
        f.manager.onDeviceReady("A")
        runCurrent()
        assertFalse(f.manager.isActive.value)
        assertFalse(f.manager.isTransmitting.value)
    }

    private class Fixture(scope: CoroutineScope) {
        val source = FakeSource()
        val port = FakePort()
        val ready = mutableSetOf("A")
        var allowed = true
        val manager =
            LocationTransmissionManager(source, { ready }, { null }, port, scope, { allowed })
    }

    private class FakeSource : LocationSource {
        override val locations = MutableSharedFlow<GeoLocation>()
        var canStart = true
        override fun start() = canStart
        override fun stop() = Unit
        override fun hasPreciseAuthorization() = true
    }

    private class FakePort : BleGattPort {
        var acceptWrites = true
        override fun writeCharacteristic(
            identifier: String,
            characteristicUuid: String,
            value: ByteArray
        ) = acceptWrites

        override fun subscribeToNotifications(identifier: String, characteristicUuid: String) =
            false

        override fun isConnected(identifier: String) = true
        override fun hasRemoteControlCharacteristic(identifier: String) = false
        override fun isRemoteFeatureActive(identifier: String) = false
        override fun setRemoteFeatureActive(identifier: String, active: Boolean) = Unit
        override fun setShutterSequenceActive(identifier: String, active: Boolean) = Unit
        override fun readCharacteristic(identifier: String, characteristicUuid: String) = false
        override fun hasCharacteristic(identifier: String, characteristicUuid: String) = true
    }

    private companion object {
        fun fix() = GeoLocation(52.52, 13.405, 5.0, Clock.System.now().toEpochMilliseconds())
    }
}
