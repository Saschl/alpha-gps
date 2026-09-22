package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Native socket implementation must bind both connections to the selected camera network. */
internal interface PtpIpPacketConnection : PtpIpCommandTransport {
    suspend fun close()
}

internal fun interface PtpIpConnectionFactory {
    suspend fun connect(): PtpIpPacketConnection
}

internal class PtpIpOpenedSession(
    val camera: PtpIpCommandAck,
    val commands: PtpIpCommandQueue,
    private val commandConnection: PtpIpPacketConnection,
    val eventConnection: PtpIpPacketConnection,
    scope: CoroutineScope,
) {
    private val sonyInitializer = SonyPtpInitializer(commands)
    val events = PtpIpEventMonitor(eventConnection, scope)
    private var closing = false

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.isClosed.first { it }
            close()
        }
    }

    suspend fun readCapabilities(): PtpIpCapabilityResult = PtpIpCapabilityReader(commands).read()
    suspend fun initializeSony(): SonyPtpInitializationResult = sonyInitializer.initialize()

    suspend fun close() {
        if (closing) return
        closing = true
        withContext(NonCancellable) {
            try {
                sonyInitializer.closeRemoteSession()
            } finally {
                commands.close()
                events.close()
                try {
                    eventConnection.close()
                } finally {
                    commandConnection.close()
                }
            }
        }
    }
}

/** Opens the two PTP/IP channels. Sony SDIO negotiation still follows this handshake. */
internal class PtpIpSessionOpener(
    private val connectionFactory: PtpIpConnectionFactory,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 20_000,
) {
    suspend fun open(clientGuid: ByteArray, clientName: String): PtpIpOpenedSession? =
        withTimeoutOrNull(timeoutMs) {
            var command: PtpIpPacketConnection? = null
            var event: PtpIpPacketConnection? = null
            try {
                command = connectionFactory.connect()
                command.send(PtpIpHandshake.commandRequest(clientGuid, clientName))
                val ack = PtpIpHandshake.parseCommandAck(command.receive())

                event = connectionFactory.connect()
                event.send(PtpIpHandshake.eventRequest(ack.connectionNumber))
                require(event.receive().type == PtpIpHandshake.INIT_EVENT_ACK)

                val session = PtpIpOpenedSession(ack, PtpIpCommandQueue(command, scope), command, event, scope)
                command = null
                event = null
                session
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                withContext(NonCancellable) {
                    try {
                        event?.close()
                    } finally {
                        command?.close()
                    }
                }
            }
        }
}
