package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidCameraConnectionFailureTest {
    private class Factory(private val create: () -> Socket) : SocketFactory() {
        override fun createSocket(): Socket = create()
        override fun createSocket(host: String, port: Int): Socket = error("Unexpected overload")
        override fun createSocket(
            host: String,
            port: Int,
            local: InetAddress,
            localPort: Int
        ): Socket = error("Unexpected overload")

        override fun createSocket(host: InetAddress, port: Int): Socket =
            error("Unexpected overload")

        override fun createSocket(
            host: InetAddress,
            port: Int,
            local: InetAddress,
            localPort: Int
        ): Socket = error("Unexpected overload")
    }

    @Test
    fun networkCreationFailuresKeepCauseAndOfferHelpWithoutDiagnosingFirewall() = runBlocking {
        for (failure in listOf(
            SocketException("socket failed: ECONNREFUSED"),
            SocketTimeoutException(),
            IOException("EACCES"),
            SecurityException(),
            IllegalStateException("Vendor wrapper", IOException()),
        )) {
            val reported = assertFailsWith<WifiRemoteConnectException> {
                AndroidCameraByteConnection.open(Factory { throw failure }, "192.168.1.1", 15740)
            }
            assertEquals(WifiRemoteFailure.NetworkConnectionFailed, reported.failure)
            assertTrue(generateSequence<Throwable>(reported) { it.cause }.any { it === failure })
        }
    }

    @Test
    fun failedConnectClosesSocketAndKeepsOriginalFailureIfCleanupAlsoFails() = runBlocking {
        val failure = IOException("Connection failed")
        var closed = false
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                throw failure
            }

            override fun close() {
                closed = true; throw IOException("Cleanup failed")
            }
        }
        val reported = assertFailsWith<WifiRemoteConnectException> {
            AndroidCameraByteConnection.open(Factory { socket }, "192.168.1.1", 15740)
        }
        assertTrue(closed)
        val causes = generateSequence<Throwable>(reported) { it.cause }.toList()
        assertTrue(causes.any { it === failure })
        assertTrue(causes.flatMap { it.suppressed.toList() }.any { it.message == "Cleanup failed" })
    }

    @Test
    fun cancellationAndProgrammingErrorsAreNotNetworkFailures() = runBlocking<Unit> {
        val cancellation = CancellationException("Stopped").apply { initCause(IOException()) }
        val reported = assertFailsWith<CancellationException> {
            AndroidCameraByteConnection.open(Factory { throw cancellation }, "192.168.1.1", 15740)
        }
        assertTrue(generateSequence<Throwable>(reported) { it.cause }.any { it === cancellation })
        assertFailsWith<IllegalStateException> {
            AndroidCameraByteConnection.open(
                Factory { error("Invalid state") },
                "192.168.1.1",
                15740
            )
        }
    }
}
