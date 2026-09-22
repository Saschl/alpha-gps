package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class SonyPtpInitializerTest {
    private class Transport : PtpIpCommandTransport {
        val sent = mutableListOf<PtpIpPacket>()
        val replies = Channel<PtpIpPacket>(Channel.UNLIMITED)
        override suspend fun send(packet: PtpIpPacket) { sent += packet }
        override suspend fun receive(): PtpIpPacket = replies.receive()

        fun reply(id: Int, data: ByteArray? = null, response: Int = 0x2001, params: List<Int> = emptyList()) {
            if (data != null) {
                val size = data.size.toLong()
                val start = byteArrayOf(id.toByte(), 0, 0, 0) +
                    ByteArray(8) { index -> (size ushr (index * 8)).toByte() }
                replies.trySend(PtpIpPacket(PtpIpPacketType.START_DATA, start))
                replies.trySend(PtpIpPacket(PtpIpPacketType.END_DATA,
                    byteArrayOf(id.toByte(), 0, 0, 0) + data))
            }
            val body = byteArrayOf(response.toByte(), (response ushr 8).toByte(), id.toByte(), 0, 0, 0) +
                params.flatMap { value -> (0..3).map { (value ushr (it * 8)).toByte() } }.toByteArray()
            replies.trySend(PtpIpPacket(PtpIpPacketType.OPERATION_RESPONSE, body))
        }
    }

    private fun deviceInfo(): ByteArray {
        val operations = listOf(0x1001, 0x1003, 0x9201, 0x9202, 0x9210, 0x9216, 0x923a, 0x9207)
        return byteArrayOf(100, 0, 6, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0,
            operations.size.toByte(), 0, 0, 0) +
            operations.flatMap { listOf(it.toByte(), (it ushr 8).toByte()) }.toByteArray()
    }

    private fun extendedInfo(): ByteArray = byteArrayOf(
        1, 0, // extension version
        1, 0, 0, 0, 0x78, 0xd2.toByte(), // one property
        2, 0, 0, 0, 0xc1.toByte(), 0xd2.toByte(), 0xc2.toByte(), 0xd2.toByte(), // S1 and S2
    )

    @Test
    fun followsA6700VersionFourBranchAndClosesSession() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val initializer = SonyPtpInitializer(queue)
        transport.reply(1, deviceInfo())
        transport.reply(2, "<Device/>".encodeToByteArray())
        transport.reply(3, "<Device><X_ServerVersion>4.00</X_ServerVersion></Device>".encodeToByteArray())
        transport.reply(4)
        transport.reply(5, ByteArray(8))
        transport.reply(6, ByteArray(8))
        transport.reply(7, params = listOf(310))
        transport.reply(8, extendedInfo(), params = listOf(1))
        transport.reply(9, ByteArray(8))

        val initialized = async { initializer.initialize() }
        runCurrent()
        val ready = assertIs<SonyPtpInitializationResult.Ready>(initialized.await())
        assertEquals("4.00", ready.serverVersion)
        assertEquals(310L, ready.vendorCodeVersion)
        assertIs<SonyPtpInitializationResult.Failed>(initializer.initialize())
        assertEquals(9, transport.sent.size)
        assertTrue(ready.extendedInfo.supportsControl(0xd2c1))
        assertTrue(ready.extendedInfo.supportsControl(0xd2c2))
        assertEquals(listOf(0x1001, 0x923a, 0x923a, 0x9210, 0x9201, 0x9201,
            0x9216, 0x9202, 0x9201), transport.sent.map { packet ->
            val body = packet.body
            (body[4].toInt() and 0xff) or ((body[5].toInt() and 0xff) shl 8)
        })
        val open = transport.sent[3].body
        assertContentEquals(byteArrayOf(1, 0, 0, 0, 2, 0, 0, 0), open.copyOfRange(10, 18))
        val extendedRequest = transport.sent[7].body
        assertContentEquals(byteArrayOf(44, 1, 0, 0, 1, 0, 0, 0), extendedRequest.copyOfRange(10, 18))

        transport.reply(10)
        val close = async { initializer.closeRemoteSession() }
        runCurrent()
        close.await()
        assertEquals(0x1003, (transport.sent.last().body[4].toInt() and 0xff) or
            ((transport.sent.last().body[5].toInt() and 0xff) shl 8))
        queue.close()
    }

    @Test
    fun rejectedConnectClosesOpenedSession() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val initializer = SonyPtpInitializer(queue)
        transport.reply(1, deviceInfo())
        transport.reply(2, "<Device/>".encodeToByteArray())
        transport.reply(3, "<X_ServerVersion>4.00</X_ServerVersion>".encodeToByteArray())
        transport.reply(4)
        transport.reply(5, response = 0x2019)
        transport.reply(6) // CloseSession in the failure path
        val initialized = async { initializer.initialize() }
        runCurrent()
        val failed = assertIs<SonyPtpInitializationResult.Rejected>(initialized.await())
        assertEquals("sdio_connect_1", failed.stage)
        assertEquals(0x2019, failed.responseCode)
        assertEquals(0x1003, (transport.sent.last().body[4].toInt() and 0xff) or
            ((transport.sent.last().body[5].toInt() and 0xff) shl 8))
        queue.close()
    }

    @Test
    fun versionIgnoresCommentsAndRejectsConflictingValues() {
        assertEquals("4.00", SonyDidVersion.parse(
            "<!-- <X_ServerVersion>1.00</X_ServerVersion> --><s:X_ServerVersion>4.00</s:X_ServerVersion>"
                .encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> {
            SonyDidVersion.parse(("<X_ServerVersion>4.00</X_ServerVersion>" +
                "<X_ServerVersion>unknown</X_ServerVersion>").encodeToByteArray())
        }
    }
}
