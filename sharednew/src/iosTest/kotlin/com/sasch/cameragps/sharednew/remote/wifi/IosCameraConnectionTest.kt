package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class IosCameraConnectionTest : WifiLoggingTest() {
    @Test fun permissionPromptCanOutlastNormalConnectionTimeout() = runTest {
        val states = Channel<IosCameraConnectionState>(Channel.UNLIMITED)
        var prompted = false
        states.send(IosCameraConnectionState.Waiting(localNetworkDenied = true))
        launch {
            delay(30.seconds)
            states.send(IosCameraConnectionState.Ready)
        }
        awaitIosCameraConnectionReady(states) { prompted = true }
        assertTrue(prompted)
        assertEquals(30_000, currentTime)
    }

    @Test fun alreadyAuthorizedConnectionDoesNotWaitForPermission() = runTest {
        val states = Channel<IosCameraConnectionState>(Channel.UNLIMITED)
        states.send(IosCameraConnectionState.Ready)
        awaitIosCameraConnectionReady(states) { fail("Permission wait was unnecessary") }
        assertEquals(0, currentTime)
    }

    @Test fun persistentDenialEndsWithPermissionError() = runTest {
        val states = Channel<IosCameraConnectionState>(Channel.UNLIMITED)
        states.send(IosCameraConnectionState.Waiting(localNetworkDenied = true))
        val failure = assertFailsWith<WifiRemoteConnectException> { awaitIosCameraConnectionReady(states) }
        assertEquals(WifiRemoteFailure.NetworkPermissionDenied, failure.failure)
        assertEquals(60_000, currentTime)
    }

    @Test fun ordinaryNetworkFailureKeepsShortTimeout() = runTest {
        val states = Channel<IosCameraConnectionState>(Channel.UNLIMITED)
        states.send(IosCameraConnectionState.Waiting(localNetworkDenied = false))
        val failure = assertFailsWith<WifiRemoteConnectException> { awaitIosCameraConnectionReady(states) }
        assertEquals(WifiRemoteFailure.CameraUnavailable, failure.failure)
        assertEquals(10_000, currentTime)
    }

    @Test fun nativeFailureIsNotMistakenForPermissionDenial() = runTest {
        val states = Channel<IosCameraConnectionState>(Channel.UNLIMITED)
        val failure = IllegalStateException("Connection refused")
        states.send(IosCameraConnectionState.Failed(failure))
        assertSame(failure, assertFailsWith<IllegalStateException> { awaitIosCameraConnectionReady(states) })
    }

    @Test fun permissionWaitCanBeCancelled() = runTest {
        val states = Channel<IosCameraConnectionState>(Channel.UNLIMITED)
        states.send(IosCameraConnectionState.Waiting(localNetworkDenied = true))
        val pending = async { awaitIosCameraConnectionReady(states) }
        runCurrent()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(0, currentTime)
    }

    private class Connection(val reply: PtpIpPacket) : PtpIpPacketConnection {
        var closed = false
        var received = false
        val sent = mutableListOf<PtpIpPacket>()
        override suspend fun send(packet: PtpIpPacket) { sent += packet }
        override suspend fun receive(): PtpIpPacket {
            if (received) awaitCancellation()
            received = true
            return reply
        }
        override suspend fun close() { closed = true }
    }

    @Test fun permissionWaitFinishesBeforeSharedHandshakeAndReusesFirstSocket() = runTest {
        val command = Connection(PtpIpPacket(PtpIpHandshake.INIT_COMMAND_ACK,
            byteArrayOf(5, 0, 0, 0) + ByteArray(16) + byteArrayOf(0x41, 0, 0, 0, 0, 0, 1, 0)))
        val event = Connection(PtpIpPacket(PtpIpHandshake.INIT_EVENT_ACK, byteArrayOf()))
        var connects = 0
        val opened = withIosCommandConnection(connect = {
            connects++
            if (connects == 1) {
                delay(30.seconds)
                assertTrue(command.sent.isEmpty())
                command
            } else event
        }) { factory ->
            assertEquals(30_000, currentTime)
            assertNotNull(PtpIpSessionOpener(factory, backgroundScope).open(ByteArray(16), "Alpha GPS"))
        }
        assertEquals(2, connects)
        assertFalse(command.closed)
        assertEquals(PtpIpHandshake.INIT_COMMAND_REQUEST, command.sent.first().type)
        opened.close()
        assertTrue(command.closed)
        assertTrue(event.closed)
    }

    @Test fun cancelledSessionSetupClosesPreparedSocket() = runTest {
        val command = Connection(PtpIpPacket(0, byteArrayOf()))
        val pending = launch {
            withIosCommandConnection(connect = { command }) { factory ->
                factory.connect()
                awaitCancellation()
            }
        }
        runCurrent()
        pending.cancelAndJoin()
        assertTrue(command.closed)
    }
}
