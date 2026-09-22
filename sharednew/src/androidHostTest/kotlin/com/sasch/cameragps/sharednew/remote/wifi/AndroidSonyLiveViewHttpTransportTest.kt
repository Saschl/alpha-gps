package com.sasch.cameragps.sharednew.remote.wifi

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSonyLiveViewHttpTransportTest {
    @Test
    fun readsChunkedHttpAndDecodesPaddedJpeg() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/live") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use {
                val frame = previewFrameFixture()
                it.write(frame, 0, 7)
                it.flush()
                it.write(frame, 7, frame.size - 7)
            }
        }
        server.start()
        try {
            val endpoint = SonyLiveViewEndpoint.fromUrl("http://127.0.0.1:${server.address.port}/live", "127.0.0.1")
            val transport = AndroidSonyLiveViewHttpTransport { it.openConnection() as HttpURLConnection }
            val stream = transport.open(endpoint)
            try {
                val decoder = SonyLiveViewFrameDecoder()
                val frames = mutableListOf<SonyLiveViewFrame>()
                withTimeout(3_000) {
                    while (true) frames += decoder.feed(stream.read() ?: break)
                }
                val frame = frames.single()
                val image = ImageIO.read(ByteArrayInputStream(frame.imageBytes))
                assertEquals(2, image.width)
                assertEquals(2, image.height)
                assertEquals(previewJpegFixture().size, frame.imageBytes.size)
            } finally { stream.close() }
        } finally { server.stop(0) }
    }

    @Test
    fun doesNotFollowRedirects() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val followed = AtomicBoolean(false)
        server.createContext("/live") {
            it.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/other")
            it.sendResponseHeaders(302, -1)
            it.close()
        }
        server.createContext("/other") { followed.set(true); it.sendResponseHeaders(200, -1); it.close() }
        server.start()
        try {
            val transport = AndroidSonyLiveViewHttpTransport { it.openConnection() as HttpURLConnection }
            val endpoint = SonyLiveViewEndpoint.fromUrl("http://127.0.0.1:${server.address.port}/live", "127.0.0.1")
            val error = assertFailsWith<SonyLiveViewHttpStatus> { transport.open(endpoint) }
            assertEquals(302, error.status)
            assertFalse(followed.get())
        } finally { server.stop(0) }
    }

    @Test
    fun cancelledOpenClosesConnectionEvenWhenStreamWasAcquired() = runBlocking {
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        val connection = object : HttpURLConnection(URL("http://127.0.0.1/live")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.set(true) }
            override fun getResponseCode() = 200
            override fun getInputStream(): ByteArrayInputStream {
                acquired.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                return ByteArrayInputStream(byteArrayOf())
            }
        }
        val transport = AndroidSonyLiveViewHttpTransport { connection }
        val open = launch(Dispatchers.Default) {
            transport.open(SonyLiveViewEndpoint.fromUrl("http://127.0.0.1/live", "127.0.0.1"))
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { acquired.await(3, TimeUnit.SECONDS) })
            open.cancel()
        } finally { release.countDown() }
        withTimeout(3_000) { open.join() }
        assertTrue(disconnected.get())
    }
}
