package com.sasch.cameragps.sharednew.remote.wifi

import com.diamondedge.logging.logging
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
    private val log = logging()

    suspend fun open(clientGuid: ByteArray, clientName: String): PtpIpOpenedSession? =
        withTimeoutOrNull(timeoutMs) {
            var command: PtpIpPacketConnection? = null
            var event: PtpIpPacketConnection? = null
            try {
                log.i { "Wi-Fi PTP opening command channel" }
                command = connectionFactory.connect()
                log.i { "Wi-Fi PTP sending command handshake" }
                command.send(PtpIpHandshake.commandRequest(clientGuid, clientName))
                val ack = PtpIpHandshake.parseCommandAck(command.receive())
                log.i { "Wi-Fi PTP command acknowledged; opening event channel" }

                event = connectionFactory.connect()
                event.send(PtpIpHandshake.eventRequest(ack.connectionNumber))
                require(event.receive().type == PtpIpHandshake.INIT_EVENT_ACK)
                log.i { "Wi-Fi PTP event handshake acknowledged" }

                val session = PtpIpOpenedSession(ack, PtpIpCommandQueue(command, scope), command, event, scope)
                command = null
                event = null
                session
            } catch (cancelled: CancellationException) {
                log.i { "Wi-Fi PTP opening cancelled or timed out: ${cancelled.message}" }
                throw cancelled
            } catch (failure: WifiRemoteConnectException) {
                log.w { "Wi-Fi PTP connection failed: ${failure.failure}" }
                throw failure
            } catch (failure: Exception) {
                log.w { "Wi-Fi PTP handshake failed: ${failure.message}" }
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
