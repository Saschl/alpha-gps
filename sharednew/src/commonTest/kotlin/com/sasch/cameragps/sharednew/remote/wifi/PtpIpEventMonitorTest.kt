package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

internal class TestEventConnection : PtpIpPacketConnection {
    val packets = Channel<PtpIpPacket>(Channel.UNLIMITED)
    val sent = mutableListOf<PtpIpPacket>()
    override suspend fun receive() = packets.receive()
    override suspend fun send(packet: PtpIpPacket) { sent += packet }
    override suspend fun close() { packets.close() }
    fun captured() { packets.trySend(PtpIpPacket(8, byteArrayOf(6, 0xc2.toByte(), 0, 0, 0, 0))) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PtpIpEventMonitorTest {
    @Test
    fun ignoresOldEventsAndRepliesToProbe() = runTest {
        val connection = TestEventConnection()
        val monitor = PtpIpEventMonitor(connection, backgroundScope)
        connection.captured()
        runCurrent()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { monitor.awaitCapture() }
        connection.packets.send(PtpIpPacket(13, ByteArray(0)))
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals(14, connection.sent.single().type)
        connection.captured()
        runCurrent()
        assertTrue(waiting.await())
        monitor.close()
    }

    @Test
    fun eventClosureReleasesCaptureWaiter() = runTest {
        val connection = TestEventConnection()
        val monitor = PtpIpEventMonitor(connection, backgroundScope)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { monitor.awaitCapture() }
        connection.close()
        runCurrent()
        assertFalse(waiting.await())
        assertTrue(monitor.isClosed.value)
        assertFalse(monitor.awaitCapture())
    }

    @Test
    fun malformedEventIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            PtpIpEvent.parse(PtpIpPacket(8, ByteArray(7)))
        }
    }
}
