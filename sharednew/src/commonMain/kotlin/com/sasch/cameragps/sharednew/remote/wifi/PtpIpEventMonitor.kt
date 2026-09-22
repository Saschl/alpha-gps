package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class PtpIpEvent(val code: Int, val transactionId: Long, val parameters: List<Long>) {
    val isCaptureComplete: Boolean get() = code == 0x400d || code == 0xc206

    companion object {
        fun parse(packet: PtpIpPacket): PtpIpEvent {
            require(packet.type == 8 && packet.body.size in 6..26 && (packet.body.size - 6) % 4 == 0)
            fun uint32(offset: Int): Long = (0..3).fold(0L) { value, index ->
                value or ((packet.body[offset + index].toLong() and 0xff) shl (index * 8))
            }
            val code = (packet.body[0].toInt() and 0xff) or ((packet.body[1].toInt() and 0xff) shl 8)
            return PtpIpEvent(code, uint32(2), (6 until packet.body.size step 4).map(::uint32))
        }
    }
}

internal sealed interface PtpIpEventSignal {
    data class Received(val event: PtpIpEvent) : PtpIpEventSignal
    data object Closed : PtpIpEventSignal
}

internal class PtpIpEventMonitor(
    private val connection: PtpIpPacketConnection,
    scope: CoroutineScope,
) {
    private val signals = MutableSharedFlow<PtpIpEventSignal>(
        extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events = signals.asSharedFlow()
    private val closed = MutableStateFlow(false)
    val isClosed = closed.asStateFlow()
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            while (true) {
                val packet = connection.receive()
                if (packet.type == 13 && packet.body.isEmpty()) {
                    connection.send(PtpIpPacket(14, ByteArray(0)))
                } else {
                    signals.emit(PtpIpEventSignal.Received(PtpIpEvent.parse(packet)))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The owning session observes Closed and tears down its command connection.
        } finally {
            closed.value = true
            signals.tryEmit(PtpIpEventSignal.Closed)
        }
    }

    suspend fun awaitCapture(timeoutMs: Long = 20_000): Boolean {
        if (closed.value) return false
        val result = withTimeoutOrNull(timeoutMs) {
            events.first { it is PtpIpEventSignal.Closed ||
                (it is PtpIpEventSignal.Received && it.event.isCaptureComplete) }
        }
        return result is PtpIpEventSignal.Received
    }

    suspend fun awaitControlResult(controlCode: Int, timeoutMs: Long): Long? {
        if (closed.value) return null
        val packed = (0x9207L shl 16) or controlCode.toLong()
        val result = withTimeoutOrNull(timeoutMs) {
            events.first { signal ->
                signal is PtpIpEventSignal.Closed || (signal is PtpIpEventSignal.Received &&
                    signal.event.code == 0xc222 && signal.event.parameters.size >= 2 &&
                    signal.event.parameters[0] == packed)
            }
        }
        return (result as? PtpIpEventSignal.Received)?.event?.parameters?.get(1)
    }

    fun close() { worker.cancel() }
}
