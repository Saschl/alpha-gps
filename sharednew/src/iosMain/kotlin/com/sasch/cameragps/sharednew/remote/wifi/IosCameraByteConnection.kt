@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sasch.cameragps.sharednew.remote.wifi

import com.diamondedge.logging.logging
import kotlinx.cinterop.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.ReceiveChannel
import platform.Network.*
import platform.darwin.*
import platform.posix.memcpy
import kotlin.time.Duration.Companion.seconds

/** Network callbacks only deliver immutable results; callers own connection state. */
internal class IosCameraByteConnection private constructor(private val connection: nw_connection_t, private val endpoint: String) : CameraByteConnection {
    private var closed = false
    private var readComplete = false
    private val log = logging()
    private val states = Channel<IosCameraConnectionState>(Channel.UNLIMITED)
    // KT-62102: the default-message sentinel crashes Kotlin/Native's Obj-C block conversion.
    private val sendContext = nw_content_context_create("camera-tcp-write").also {
        nw_content_context_set_is_final(it, false)
    }

    private suspend fun start() {
        log.i { "Wi-Fi TCP opening $endpoint (plain TCP)" }
        nw_connection_set_queue(connection, dispatch_get_main_queue())
        nw_connection_set_state_changed_handler(connection) { state, error ->
            val path = nw_connection_copy_current_path(connection)
            val reason = path?.let { nw_path_get_unsatisfied_reason(it) }
            val stateName = when (state) {
                nw_connection_state_preparing -> "preparing"
                nw_connection_state_waiting -> "waiting"
                nw_connection_state_ready -> "ready"
                nw_connection_state_failed -> "failed"
                nw_connection_state_cancelled -> "cancelled"
                else -> state.toString()
            }
            log.i { "Wi-Fi TCP $endpoint state=$stateName pathReason=$reason ${networkErrorDescription(error)}" }
            when (state) {
                nw_connection_state_ready -> states.trySend(IosCameraConnectionState.Ready)
                nw_connection_state_waiting -> states.trySend(IosCameraConnectionState.Waiting(
                    reason == nw_path_unsatisfied_reason_local_network_denied))
                nw_connection_state_failed, nw_connection_state_cancelled -> states.trySend(
                    IosCameraConnectionState.Failed(IllegalStateException("Camera connection failed: ${networkErrorDescription(error)}")))
            }
        }
        nw_connection_start(connection)
        awaitIosCameraConnectionReady(states) {
            log.i { "Wi-Fi TCP $endpoint waiting for Local Network permission; keeping connection open" }
        }
        log.i { "Wi-Fi TCP ready $endpoint" }
    }

    override suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val result = Channel<String?>(1)
        val data = bytes.usePinned {
            dispatch_data_create(it.addressOf(0), bytes.size.toULong(), dispatch_get_main_queue(), null)
        }
        try {
            nw_connection_send(connection, data, sendContext, true) { error ->
                result.trySend(if (error == null) null else networkErrorDescription(error))
            }
            withTimeout(10.seconds) {
                val error = result.receive()
                check(error == null) { "Camera write failed: $error" }
            }
        } catch (failure: Throwable) {
            log.w { "Wi-Fi TCP $endpoint interrupted: ${failure.message}" }
            close()
            throw failure
        } finally {
            result.close()
        }
    }

    override suspend fun read(): ByteArray? {
        if (readComplete) return null
        val result = Channel<Result<ReceivedContent>>(1)
        nw_connection_receive(connection, 1u, 16u * 1024u) { data, context, isComplete, error ->
            val bytes = data?.let { content ->
                val size = dispatch_data_get_size(content).toInt()
                if (size == 0) null else ByteArray(size).also { output ->
                    output.usePinned { pinned ->
                        dispatch_data_apply(content) { _, offset, pointer, length ->
                            memcpy(pinned.addressOf(offset.toInt()), pointer, length)
                            true
                        }
                    }
                }
            }
            val endOfStream = isComplete && context != null && nw_content_context_get_is_final(context)
            result.trySend(if (error != null) Result.failure(IllegalStateException("Camera read failed: ${networkErrorDescription(error)}"))
                else Result.success(ReceivedContent(bytes, endOfStream)))
        }
        try {
            val content = result.receive().getOrThrow()
            readComplete = content.endOfStream
            if (readComplete) log.i { "Wi-Fi TCP EOF $endpoint" }
            return content.bytes
        } catch (failure: Throwable) {
            log.w { "Wi-Fi TCP $endpoint interrupted: ${failure.message}" }
            close()
            throw failure
        } finally {
            result.close()
        }
    }

    private data class ReceivedContent(val bytes: ByteArray?, val endOfStream: Boolean)

    override suspend fun close() {
        if (closed) return
        closed = true
        log.i { "Wi-Fi TCP closing $endpoint" }
        nw_connection_cancel(connection)
        nw_connection_set_state_changed_handler(connection, null)
        states.close()
    }

    companion object {
        suspend fun open(
            host: String,
            port: Int,
            parameters: nw_parameters_t = iosCameraTcpParameters(),
        ): IosCameraByteConnection {
            require(port in 1..65535)
            val endpoint = nw_endpoint_create_host(host, port.toString())
            val connection = IosCameraByteConnection(checkNotNull(nw_connection_create(endpoint, parameters)), "$host:$port")
            try {
                connection.start()
                return connection
            } catch (failure: Throwable) {
                connection.close()
                throw failure
            }
        }
    }
}

private fun networkErrorDescription(error: nw_error_t): String =
    if (error == null) "error=none" else "errorDomain=${nw_error_get_error_domain(error)} errorCode=${nw_error_get_error_code(error)}"

internal sealed interface IosCameraConnectionState {
    data object Ready : IosCameraConnectionState
    data class Waiting(val localNetworkDenied: Boolean) : IosCameraConnectionState
    data class Failed(val error: Exception) : IosCameraConnectionState
}

/** A denied path also occurs while the first permission prompt is still open. */
internal suspend fun awaitIosCameraConnectionReady(
    states: ReceiveChannel<IosCameraConnectionState>,
    onPermissionPending: () -> Unit = {},
) {
    val first = withTimeoutOrNull(10.seconds) {
        while (true) {
            when (val state = states.receive()) {
                IosCameraConnectionState.Ready -> return@withTimeoutOrNull true
                is IosCameraConnectionState.Failed -> throw state.error
                is IosCameraConnectionState.Waiting -> if (state.localNetworkDenied) return@withTimeoutOrNull false
            }
        }
        @Suppress("UNREACHABLE_CODE")
        false
    } ?: throw WifiRemoteConnectException(WifiRemoteFailure.CameraUnavailable)
    if (first) return
    onPermissionPending()
    val ready = withTimeoutOrNull(60.seconds) {
        while (true) {
            when (val state = states.receive()) {
                IosCameraConnectionState.Ready -> return@withTimeoutOrNull true
                is IosCameraConnectionState.Failed -> throw state.error
                is IosCameraConnectionState.Waiting -> Unit
            }
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }
    if (ready != true) throw WifiRemoteConnectException(WifiRemoteFailure.NetworkPermissionDenied)
}

internal fun iosCameraTcpParameters(): nw_parameters_t {
    // Kotlin/Native wraps the disable-TLS block sentinel, losing its native identity.
    val parameters = nw_parameters_create()
    val stack = nw_parameters_copy_default_protocol_stack(parameters)
    nw_protocol_stack_set_transport_protocol(stack, nw_tcp_create_options())
    nw_parameters_set_required_interface_type(parameters, nw_interface_type_wifi)
    return parameters
}
