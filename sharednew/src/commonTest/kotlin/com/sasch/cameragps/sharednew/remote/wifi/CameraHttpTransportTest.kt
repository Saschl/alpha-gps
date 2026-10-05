package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CameraHttpTransportTest : WifiLoggingTest() {
    private class Connection(response: String, fragmentSize: Int = 1) : CameraByteConnection {
        val chunks = ArrayDeque(response.encodeToByteArray().asList().chunked(fragmentSize).map { it.toByteArray() })
        var request = ""
        var closed = false
        override suspend fun write(bytes: ByteArray) { request += bytes.decodeToString() }
        override suspend fun read() = chunks.removeFirstOrNull()
        override suspend fun close() { closed = true }
    }
    private suspend fun open(connection: Connection) = CameraHttpTransport { host, port ->
        assertEquals("192.168.0.1", host)
        assertEquals(8080, port)
        connection
    }.open(SonyLiveViewEndpoint.fromUrl("http://192.168.0.1:8080/live?frame=1", "192.168.0.1"))

    private suspend fun SonyLiveViewByteStream.body(): String = buildString {
        while (true) append((read() ?: break).decodeToString())
    }

    @Test fun fragmentedChunkedBodyAndRequest() = runTest {
        val connection = Connection("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3;foo=bar\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")
        val stream = open(connection)
        assertEquals("abcde", stream.body())
        assertTrue(connection.request.startsWith("GET /live?frame=1 HTTP/1.1\r\nHost: 192.168.0.1:8080\r\n"))
        stream.close()
        assertTrue(connection.closed)
    }
    @Test fun contentLengthStopsBeforeTrailingBytes() = runTest {
        assertEquals("abc", open(Connection("HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabcEXTRA", 1024)).body())
    }
    @Test fun unframedBodyEndsAtEof() = runTest {
        assertEquals("abc", open(Connection("HTTP/1.0 200 OK\r\n\r\nabc")).body())
    }
    @Test fun rejectsTruncatedBodies() = runTest {
        for (body in listOf("Content-Length: 9\r\n\r\nabc", "Transfer-Encoding: chunked\r\n\r\n9\r\nabc")) {
            assertFailsWith<IllegalStateException> { open(Connection("HTTP/1.1 200 OK\r\n$body")).body() }
        }
    }
    @Test fun closesConnectionOnInvalidHeaders() = runTest {
        for (header in listOf(
            "Content-Length: 1\r\nContent-Length: 1",
            "Content-Length: 1\r\nTransfer-Encoding: chunked",
            "Content-Encoding: gzip", "Content-Length: -1", "Transfer-Encoding: gzip",
        )) {
            val connection = Connection("HTTP/1.1 200 OK\r\n$header\r\n\r\n")
            assertFails { open(connection) }
            assertTrue(connection.closed)
        }
    }
    @Test fun refusesRedirects() = runTest {
        val connection = Connection("HTTP/1.1 302 Found\r\nLocation: http://example.com/\r\n\r\n")
        assertFailsWith<SonyLiveViewHttpStatus> { open(connection) }
        assertTrue(connection.closed)
    }
    @Test fun boundsHeadersAndChunks() = runTest {
        assertFails { open(Connection("HTTP/1.1 200 OK\r\nX: ${"a".repeat(8192)}\r\n\r\n")) }
        assertFails { open(Connection("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nFFFFFFFF\r\n")).body() }
    }
}
