package com.sasch.cameragps.sharednew.remote.wifi

import java.net.ServerSocket
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AndroidCameraHttpTransportTest {
    private suspend fun response(bytes: ByteArray, test: suspend (SonyLiveViewByteStream) -> Unit) {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { peer ->
                    peer.soTimeout = 3_000
                    val reader = peer.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val output = peer.getOutputStream()
                    for (chunk in bytes.asList().chunked(7)) output.write(chunk.toByteArray())
                }
            }
            try {
                val transport = AndroidCameraHttpTransport(SocketFactory.getDefault())
                val endpoint = SonyLiveViewEndpoint.fromUrl("http://127.0.0.1:${server.localPort}/live", "127.0.0.1")
                val stream = transport.open(endpoint)
                try { test(stream) } finally { stream.close() }
            } finally { worker.join(3_000) }
        }
    }

    @Test
    fun parsesChunkedHttpAcrossFragments() = runBlocking {
        val frame = previewFrameFixture()
        val header = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
        val body = "${frame.size.toString(16)};frame=1\r\n".toByteArray() + frame + "\r\n0\r\n\r\n".toByteArray()
        response(header.toByteArray() + body) { stream ->
            val decoder = SonyLiveViewFrameDecoder()
            val frames = mutableListOf<SonyLiveViewFrame>()
            withTimeout(3_000) { while (true) frames += decoder.feed(stream.read() ?: break) }
            assertContentEquals(previewJpegFixture(), frames.single().imageBytes)
        }
    }

    @Test
    fun enforcesContentLengthAndRejectsTruncation() = runBlocking {
        response("HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabcEXTRA".toByteArray()) { stream ->
            val bytes = mutableListOf<Byte>()
            while (true) bytes += (stream.read() ?: break).toList()
            assertEquals("abc", bytes.toByteArray().decodeToString())
        }
        response("HTTP/1.1 200 OK\r\nContent-Length: 99\r\n\r\nabc".toByteArray()) { stream ->
            assertFailsWith<java.io.EOFException> { while (stream.read() != null) { } }
        }
    }

    @Test
    fun rejectsRedirectsAndAmbiguousTransferFraming(): Unit = runBlocking {
        assertFailsWith<SonyLiveViewHttpStatus> {
            response("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.2/live\r\n\r\n".toByteArray()) { }
        }
        assertFailsWith<IllegalArgumentException> {
            response("HTTP/1.1 200 OK\r\nContent-Length: 9\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()) { }
        }
    }
}
