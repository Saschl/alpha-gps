package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PtpIpSessionOpenerTest {
    private class FakeConnection : PtpIpPacketConnection {
        val sent = mutableListOf<PtpIpPacket>()
        val replies = Channel<PtpIpPacket>(Channel.UNLIMITED)
        var closed = false

        override suspend fun send(packet: PtpIpPacket) { sent += packet }
        override suspend fun receive(): PtpIpPacket = replies.receive()
        override suspend fun close() { closed = true; replies.close() }
    }

    private fun commandAck(): PtpIpPacket = PtpIpPacket(
        PtpIpHandshake.INIT_COMMAND_ACK,
        byteArrayOf(5, 0, 0, 0) + ByteArray(16) + byteArrayOf(0x41, 0, 0, 0, 0, 0, 1, 0),
    )

    @Test
    fun opensCommandThenEventWithAcknowledgedConnectionNumber() = runTest {
        val command = FakeConnection().apply { replies.trySend(commandAck()) }
        val event = FakeConnection().apply { replies.trySend(PtpIpPacket(PtpIpHandshake.INIT_EVENT_ACK, byteArrayOf())) }
        val connections = ArrayDeque(listOf(command, event))
        val opener = PtpIpSessionOpener(PtpIpConnectionFactory { connections.removeFirst() }, backgroundScope)

        val session = assertNotNull(opener.open(ByteArray(16), "AlphaGPS"))
        assertEquals(PtpIpHandshake.INIT_COMMAND_REQUEST, command.sent.single().type)
        assertEquals(PtpIpHandshake.INIT_EVENT_REQUEST, event.sent.single().type)
        assertEquals(5, event.sent.single().body[0].toInt())
        assertEquals(5, session.camera.connectionNumber)
        session.close()
        assertTrue(command.closed)
        assertTrue(event.closed)
    }

    @Test
    fun initFailureClosesOnlyOpenedConnection() = runTest {
        val command = FakeConnection().apply { replies.trySend(PtpIpPacket(PtpIpHandshake.INIT_FAIL, byteArrayOf(1, 0, 0, 0))) }
        val opener = PtpIpSessionOpener(PtpIpConnectionFactory { command }, backgroundScope)
        assertNull(opener.open(ByteArray(16), "AlphaGPS"))
        assertTrue(command.closed)
    }

    @Test
    fun lostEventConnectionClosesCommandConnection() = runTest {
        val command = FakeConnection().apply { replies.trySend(commandAck()) }
        val event = FakeConnection().apply { replies.trySend(PtpIpPacket(PtpIpHandshake.INIT_EVENT_ACK, byteArrayOf())) }
        val connections = ArrayDeque(listOf(command, event))
        val opener = PtpIpSessionOpener(PtpIpConnectionFactory { connections.removeFirst() }, backgroundScope)
        assertNotNull(opener.open(ByteArray(16), "AlphaGPS"))
        event.replies.close()
        runCurrent()
        assertTrue(command.closed)
        assertTrue(event.closed)
    }
}
