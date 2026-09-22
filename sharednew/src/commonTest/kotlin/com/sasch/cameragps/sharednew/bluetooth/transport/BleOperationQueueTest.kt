package com.sasch.cameragps.sharednew.bluetooth.transport

import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.PlatformLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BleOperationQueueTest {
    private class Transport : BlePeripheralTransport {
        override val events = emptyFlow<BleTransportEvent>()
        val writes = mutableListOf<String>()
        override fun isConnected(identifier: String) = true
        override fun hasCharacteristic(identifier: String, characteristicUuid: String) = true
        override fun initiateWrite(identifier: String, characteristicUuid: String, value: ByteArray): Boolean {
            writes += characteristicUuid
            return true
        }
        override fun initiateRead(identifier: String, characteristicUuid: String) = true
        override fun initiateSubscribe(identifier: String, characteristicUuid: String, enable: Boolean) = true
        override fun initiateDiscoverServices(identifier: String) = true
    }

    @BeforeTest fun silenceLogs() { KmLogging.setLoggers() }
    @AfterTest fun restoreLogs() { KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true))) }

    @Test
    fun cancelledParkedWifiStartNeverExecutesButInflightWriteDrains() = runTest {
        val transport = Transport()
        val queue = BleOperationQueue(transport, backgroundScope)
        val inflight = launch { queue.execute("A", BleOperation.Write("first", byteArrayOf(1))) }
        runCurrent()
        val parked = launch { queue.execute("A", BleOperation.Write("wifi-start", byteArrayOf(1))) }
        runCurrent()
        parked.cancelAndJoin()
        inflight.cancelAndJoin()
        val next = launch { queue.execute("A", BleOperation.Write("next", byteArrayOf(1))) }
        runCurrent()
        assertEquals(listOf("first"), transport.writes)
        queue.onTransportEvent(BleTransportEvent.CharacteristicWritten("A", "first", BleOperationStatus.Success))
        runCurrent()
        assertEquals(listOf("first", "next"), transport.writes)
        queue.onTransportEvent(BleTransportEvent.CharacteristicWritten("A", "next", BleOperationStatus.Success))
        next.join()
    }
}
