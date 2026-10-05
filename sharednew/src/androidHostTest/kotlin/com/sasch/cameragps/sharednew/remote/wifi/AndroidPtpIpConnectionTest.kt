package com.sasch.cameragps.sharednew.remote.wifi

import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AndroidPtpIpConnectionTest {
    @Test
    fun preservesPacketsAcrossFragmentedAndCombinedTcpReads() = runBlocking {
        ServerSocket(0).use { server ->
            val first = PtpIpPacket(7, byteArrayOf(1, 2, 3))
            val second = PtpIpPacket(8, byteArrayOf(4, 5))
            val encoded = PtpIpPacketCodec.encode(first) + PtpIpPacketCodec.encode(second)
            val worker = thread {
                server.accept().use { peer ->
                    peer.getOutputStream().write(encoded, 0, 3)
                    peer.getOutputStream().write(encoded, 3, encoded.size - 3)
                }
            }
            val socket = Socket("127.0.0.1", server.localPort)
            val connection = AndroidPtpIpConnection(socket)
            try {
                val receivedFirst = withTimeout(3_000) { connection.receive() }
                val receivedSecond = withTimeout(3_000) { connection.receive() }
                assertEquals(first.type, receivedFirst.type)
                assertContentEquals(first.body, receivedFirst.body)
                assertEquals(second.type, receivedSecond.type)
                assertContentEquals(second.body, receivedSecond.body)
            } finally {
                connection.close()
                worker.join(3_000)
            }
        }
    }
}
