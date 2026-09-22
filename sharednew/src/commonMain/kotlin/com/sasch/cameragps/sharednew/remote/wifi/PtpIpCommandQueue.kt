package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/** One instance belongs to one TCP command connection. The transport performs socket I/O off Main. */
internal interface PtpIpCommandTransport {
    suspend fun send(packet: PtpIpPacket)
    suspend fun receive(): PtpIpPacket
}

internal sealed interface PtpIpTransactionResult {
    data class Response(val response: PtpIpOperationResponse, val data: ByteArray? = null) : PtpIpTransactionResult
    /** The camera may have acted; in particular, never automatically retry a capture. */
    data object Uncertain : PtpIpTransactionResult
    data class Failure(val reason: String) : PtpIpTransactionResult
    data object Closed : PtpIpTransactionResult
}

/** Serial command lane with bounded waiting and per-connection transaction IDs. */
internal class PtpIpCommandQueue(
    private val transport: PtpIpCommandTransport,
    scope: CoroutineScope,
    private val timeoutMs: Long = 15_000,
) {
    init {
        require(timeoutMs > 0)
    }

    private class Pending(val code: Int, val parameters: List<Long>, val dataIn: Boolean,
                          val dataOut: ByteArray?, val timeoutMs: Long) {
        val result = CompletableDeferred<PtpIpTransactionResult>()
    }

    private val requests = Channel<Pending>(64)
    private var nextTransactionId = 1
    private var active: Pending? = null
    private val worker: Job = scope.launch {
        try {
            for (pending in requests) {
                if (pending.result.isCancelled) continue
                active = pending
                val result = try {
                    withTimeout(pending.timeoutMs.milliseconds) {
                        require(nextTransactionId > 0) { "PTP/IP transaction IDs exhausted; reopen the connection" }
                        val transactionId = nextTransactionId++
                        val dataIn = if (pending.dataIn) PtpIpDataIn(transactionId) else null
                        transport.send(PtpIpOperations.request(pending.code, transactionId, pending.parameters,
                            dataOut = pending.dataOut != null))
                        pending.dataOut?.let { bytes ->
                            transport.send(PtpIpOperations.startData(transactionId, bytes.size))
                            transport.send(PtpIpOperations.endData(transactionId, bytes))
                        }
                        while (true) {
                            val packet = transport.receive()
                            if (packet.type in listOf(PtpIpPacketType.START_DATA, PtpIpPacketType.DATA, PtpIpPacketType.END_DATA)) {
                                val dataId = packet.dataTransactionId()
                                if (dataId < transactionId) continue
                                require(dataId == transactionId) { "Future transaction data" }
                                require(dataIn != null) { "Unexpected data transfer" }
                                dataIn.consume(packet)
                                continue
                            }
                            require(packet.type == PtpIpPacketType.OPERATION_RESPONSE) { "Unexpected command packet" }
                            val response = PtpIpOperations.response(packet)
                            if (response.transactionId < transactionId) continue // late response to timed-out request
                            require(response.transactionId == transactionId) { "Future transaction response" }
                            return@withTimeout PtpIpTransactionResult.Response(response, dataIn?.completedData())
                        }
                        @Suppress("UNREACHABLE_CODE")
                        PtpIpTransactionResult.Uncertain
                    }
                } catch (_: TimeoutCancellationException) {
                    PtpIpTransactionResult.Uncertain
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Native socket exception text can contain network names or addresses.
                    PtpIpTransactionResult.Failure("PTP/IP transaction failed")
                }
                pending.result.complete(result)
                active = null
            }
        } finally {
            requests.close()
            active?.result?.complete(PtpIpTransactionResult.Closed)
            active = null
            while (true) {
                val pending = requests.tryReceive().getOrNull() ?: break
                pending.result.complete(PtpIpTransactionResult.Closed)
            }
        }
    }

    suspend fun executeNoData(code: Int, parameters: List<Long> = emptyList()): PtpIpTransactionResult {
        return execute(code, parameters, dataIn = false, dataOut = null)
    }

    suspend fun executeDataIn(
        code: Int, parameters: List<Long> = emptyList(),
        operationTimeoutMs: Long = timeoutMs
    ): PtpIpTransactionResult =
        execute(
            code,
            parameters,
            dataIn = true,
            dataOut = null,
            operationTimeoutMs = operationTimeoutMs
        )

    suspend fun executeDataOut(code: Int, parameters: List<Long>, data: ByteArray,
                               operationTimeoutMs: Long = timeoutMs): PtpIpTransactionResult {
        require(data.size <= PtpIpPacketCodec.MAX_PACKET_BYTES - 12)
        return execute(code, parameters, dataIn = false, dataOut = data.copyOf(),
            operationTimeoutMs = operationTimeoutMs)
    }

    private suspend fun execute(code: Int, parameters: List<Long>, dataIn: Boolean,
                                dataOut: ByteArray?, operationTimeoutMs: Long = timeoutMs): PtpIpTransactionResult {
        require(operationTimeoutMs > 0)
        val pending = Pending(code, parameters.toList(), dataIn, dataOut, operationTimeoutMs)
        if (!requests.trySend(pending).isSuccess) return PtpIpTransactionResult.Closed
        return try {
            pending.result.await()
        } catch (cancelled: CancellationException) {
            pending.result.cancel()
            throw cancelled
        }
    }

    fun close() {
        requests.close()
        active?.result?.complete(PtpIpTransactionResult.Closed)
        while (true) {
            val pending = requests.tryReceive().getOrNull() ?: break
            pending.result.complete(PtpIpTransactionResult.Closed)
        }
        worker.cancel()
    }
}
