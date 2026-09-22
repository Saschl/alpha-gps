package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.decodeToImageBitmap

internal interface SonyLiveViewByteStream {
    /** Each chunk is owned by the caller and bounded to 64 KiB. Null means EOF. */
    suspend fun read(): ByteArray?
    suspend fun close()
}

internal fun interface SonyLiveViewHttpTransport {
    suspend fun open(endpoint: SonyLiveViewEndpoint): SonyLiveViewByteStream
}

internal class SonyLiveViewHttpStatus(val status: Int) : Exception("Live-view HTTP status $status")

/** Collect on the session's Main.immediate scope. Leaving collection releases preview controls. */
internal class SonyLiveViewStream(
    private val commands: PtpIpCommandQueue,
    private val capabilities: SonyPtpInitializationResult.Ready,
    private val events: PtpIpEventMonitor,
    private val http: SonyLiveViewHttpTransport,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var collecting = false

    fun images(endpoint: SonyLiveViewEndpoint): Flow<ImageBitmap> = frames(endpoint).map { frame ->
        withContext(decodeDispatcher) {
            frame.imageBytes.decodeToImageBitmap().also {
                require(it.width == frame.width && it.height == frame.height) { "Invalid decoded image size" }
            }
        }
    }

    fun frames(endpoint: SonyLiveViewEndpoint): Flow<SonyLiveViewFrame> = flow {
        coroutineScope {
            check(!collecting) { "Live view is already running" }
            require(capabilities.deviceInfo.supports(SonyPtpOperation.SDIO_CONTROL_DEVICE) &&
                capabilities.extendedInfo.supportsControl(SonyPtpControlCode.LIVE_VIEW_ENABLE)) {
                "Camera does not advertise live view"
            }
            collecting = true
            var stream: SonyLiveViewByteStream? = null
            var postviewAttempted = false
            var liveviewAttempted = false
            val eventWatcher = launch {
                events.isClosed.first { it }
                error("Live-view event channel closed")
            }
            try {
                if (capabilities.extendedInfo.supportsControl(POSTVIEW_ENABLE)) {
                    postviewAttempted = true
                    coroutineScope {
                        val result = async(start = CoroutineStart.UNDISPATCHED) {
                            events.awaitControlResult(POSTVIEW_ENABLE, timeoutMs = 1_000)
                        }
                        try {
                            if (accepted(control(POSTVIEW_ENABLE, true))) result.await()
                        } finally {
                            result.cancel()
                        }
                    }
                }
                liveviewAttempted = true
                check(accepted(control(SonyPtpControlCode.LIVE_VIEW_ENABLE, true))) { "Live-view enable failed" }
                val opened = openWithRetry(endpoint)
                stream = opened
                val decoder = SonyLiveViewFrameDecoder()
                while (true) {
                    val frames = withTimeout(10_000) {
                        var decoded = emptyList<SonyLiveViewFrame>()
                        while (decoded.isEmpty()) {
                            val chunk = opened.read() ?: error("Live-view stream closed")
                            require(chunk.isNotEmpty() && chunk.size <= 64 * 1024) { "Invalid live-view chunk" }
                            decoded = withContext(decodeDispatcher) { decoder.feed(chunk) }
                        }
                        decoded
                    }
                    for (frame in frames) emit(frame)
                }
            } finally {
                eventWatcher.cancel()
                withContext(NonCancellable) {
                    try {
                        stream?.close()
                    } finally {
                        try {
                            if (liveviewAttempted) control(SonyPtpControlCode.LIVE_VIEW_ENABLE, false)
                        } finally {
                            try {
                                if (postviewAttempted) control(POSTVIEW_ENABLE, false)
                            } finally {
                                collecting = false
                            }
                        }
                    }
                }
            }
        }
    }.buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private suspend fun openWithRetry(endpoint: SonyLiveViewEndpoint): SonyLiveViewByteStream {
        var opened: SonyLiveViewByteStream? = null
        try {
            return withTimeout(15_000) {
                repeat(3) { attempt ->
                    try {
                        val stream = http.open(endpoint)
                        opened = stream
                        return@withTimeout stream
                    } catch (failure: SonyLiveViewHttpStatus) {
                        if (failure.status != 503 || attempt == 2) throw failure
                        delay(500)
                    }
                }
                error("Live-view open failed")
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { opened?.close() }
            throw failure
        }
    }

    private suspend fun control(code: Int, down: Boolean): PtpIpTransactionResult {
        val params = if ((capabilities.vendorCodeVersion ?: 0) >= 310) listOf(code.toLong(), 1L)
        else listOf(code.toLong())
        return commands.executeDataOut(SonyPtpOperation.SDIO_CONTROL_DEVICE, params,
            byteArrayOf(if (down) 2 else 1, 0), operationTimeoutMs = 3_000)
    }

    private fun accepted(result: PtpIpTransactionResult) =
        result is PtpIpTransactionResult.Response && result.response.code == 0x2001

    private companion object { const val POSTVIEW_ENABLE = 0xd312 }
}
