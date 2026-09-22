package com.sasch.cameragps.sharednew.remote.wifi

import android.net.Network
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Uses the camera Network for HTTP just as the PTP sockets do. Redirects are never followed. */
internal class AndroidSonyLiveViewHttpTransport internal constructor(
    private val openConnection: (URL) -> HttpURLConnection,
) : SonyLiveViewHttpTransport {
    constructor(network: Network) : this({ network.openConnection(it) as HttpURLConnection })

    override suspend fun open(endpoint: SonyLiveViewEndpoint): SonyLiveViewByteStream {
        var connection: HttpURLConnection? = null
        try {
            return withContext(Dispatchers.IO) {
                val opened = openConnection(URL(endpoint.url))
                connection = opened
                opened.instanceFollowRedirects = false
                opened.connectTimeout = 10_000
                opened.readTimeout = 1_000
                opened.useCaches = false
                opened.setRequestProperty("Connection", "close")
                val status = opened.responseCode
                if (status != 200) throw SonyLiveViewHttpStatus(status)
                AndroidSonyLiveViewByteStream(opened, opened.inputStream)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { connection?.disconnect() }
            throw failure
        }
    }
}

private class AndroidSonyLiveViewByteStream(
    private val connection: HttpURLConnection,
    private val input: InputStream,
) : SonyLiveViewByteStream {
    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + 10_000_000_000L
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val size = input.read(buffer)
                if (size < 0) return@withContext null
                if (size > 0) return@withContext buffer.copyOf(size)
            } catch (_: SocketTimeoutException) {
                // Retain partial frame state in the shared decoder while checking cancellation.
            }
            if (System.nanoTime() >= deadline) throw SocketTimeoutException("Live-view read timed out")
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        try { input.close() } finally { connection.disconnect() }
    }
}
