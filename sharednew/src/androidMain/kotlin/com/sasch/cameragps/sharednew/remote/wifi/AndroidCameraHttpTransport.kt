package com.sasch.cameragps.sharednew.remote.wifi

import java.io.BufferedInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.SocketFactory
import kotlinx.coroutines.*

/** Plain HTTP is confined to a validated camera peer and its network, without a global cleartext exception. */
internal class AndroidCameraHttpTransport(private val sockets: SocketFactory) : SonyLiveViewHttpTransport {
    override suspend fun open(endpoint: SonyLiveViewEndpoint): SonyLiveViewByteStream {
        var socket: Socket? = null
        try {
            return withContext(Dispatchers.IO) {
                val uri = URI(endpoint.url)
                val opened = sockets.createSocket()
                socket = opened
                opened.connect(InetSocketAddress(uri.host, if (uri.port == -1) 80 else uri.port), 10_000)
                opened.soTimeout = 1_000
                val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
                opened.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\n" +
                    "Connection: close\r\nAccept-Encoding: identity\r\n\r\n").toByteArray(Charsets.US_ASCII))
                val body = CameraHttpBody(opened)
                body.readHeaders()
                body
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { socket?.close() }
            throw failure
        }
    }
}

private class CameraHttpBody(private val socket: Socket) : SonyLiveViewByteStream {
    private val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
    private var chunked = false
    private var remaining = Long.MAX_VALUE
    private var chunkRemaining = 0L
    private var chunkTerminator = false
    private var ended = false

    suspend fun readHeaders() {
        val deadline = deadline()
        val status = line(deadline).split(' ')
        require(status.size >= 2 && status[0] in listOf("HTTP/1.1", "HTTP/1.0")) { "Invalid camera HTTP status" }
        val code = status[1].toIntOrNull() ?: error("Invalid camera HTTP status")
        if (code != 200) throw SonyLiveViewHttpStatus(code)
        val headers = mutableMapOf<String, String>()
        var bytes = 0
        while (true) {
            val line = line(deadline)
            bytes += line.length + 2
            require(bytes <= 16 * 1024) { "Camera HTTP headers too large" }
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            require(colon > 0) { "Invalid camera HTTP header" }
            val key = line.substring(0, colon).lowercase()
            if (key in setOf("transfer-encoding", "content-length", "content-encoding")) {
                require(key !in headers) { "Ambiguous camera HTTP framing" }
                headers[key] = line.substring(colon + 1).trim().lowercase()
            }
        }
        require(headers["content-encoding"] in listOf(null, "identity")) { "Unsupported camera HTTP encoding" }
        val transfer = headers["transfer-encoding"]
        require(transfer in listOf(null, "chunked")) { "Unsupported camera HTTP transfer" }
        chunked = transfer == "chunked"
        require(!chunked || "content-length" !in headers) { "Ambiguous camera HTTP framing" }
        headers["content-length"]?.let {
            remaining = it.toLongOrNull()?.takeIf { size -> size >= 0 } ?: error("Invalid camera HTTP length")
        }
    }

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        if (ended || remaining == 0L) return@withContext null
        val deadline = deadline()
        if (chunked && chunkRemaining == 0L) {
            if (chunkTerminator) require(line(deadline).isEmpty()) { "Invalid camera HTTP chunk terminator" }
            val length = line(deadline).substringBefore(';')
            require(length.isNotEmpty() && length.all { it in "0123456789abcdefABCDEF" }) { "Invalid camera HTTP chunk" }
            chunkRemaining = length.toLongOrNull(16)?.takeIf { it in 0..8L * 1024 * 1024 }
                ?: error("Camera HTTP chunk too large")
            if (chunkRemaining == 0L) { ended = true; return@withContext null }
            chunkTerminator = true
        }
        val limit = minOf(16 * 1024L, if (chunked) chunkRemaining else remaining).toInt()
        val buffer = ByteArray(limit)
        var count: Int
        while (true) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() > deadline) throw SocketTimeoutException("Camera HTTP timed out")
            try { count = input.read(buffer); break } catch (_: SocketTimeoutException) { }
        }
        if (count < 0) {
            if (chunked || remaining != Long.MAX_VALUE) throw EOFException("Camera HTTP truncated")
            ended = true
            return@withContext null
        }
        if (chunked) chunkRemaining -= count else if (remaining != Long.MAX_VALUE) remaining -= count
        buffer.copyOf(count)
    }

    private fun deadline() = System.nanoTime() + 10_000_000_000L

    private suspend fun line(deadline: Long): String {
        val text = StringBuilder()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() > deadline) throw SocketTimeoutException("Camera HTTP timed out")
            val byte = try { input.read() } catch (_: SocketTimeoutException) { continue }
            if (byte < 0) throw EOFException("Camera HTTP truncated")
            if (byte == 10) {
                require(text.endsWith("\r")) { "Invalid camera HTTP line" }
                return text.dropLast(1).toString()
            }
            require(text.length < 8192) { "Camera HTTP line too large" }
            text.append(byte.toChar())
        }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) { socket.close() }
}
