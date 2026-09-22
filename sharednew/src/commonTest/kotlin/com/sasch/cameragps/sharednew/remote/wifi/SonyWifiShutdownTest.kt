package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SonyWifiShutdownTest {
    private fun number(value: Long, size: Int) = ByteArray(size) { (value ushr (8 * it)).toByte() }
    private fun property(code: Int, value: Int = 1, enabled: Int = 1): ByteArray =
        number(code.toLong(), 2) + number(4, 2) + byteArrayOf(0, enabled.toByte()) +
                number(0, 2) + number(value.toLong(), 2) + byteArrayOf(0)

    private fun dataset(vararg records: ByteArray) =
        number(records.size.toLong(), 8) + records.fold(byteArrayOf()) { a, b -> a + b }

    private inner class Transport(var properties: ByteArray) : PtpIpCommandTransport {
        val packets = mutableListOf<PtpIpPacket>()
        val replies = Channel<PtpIpPacket>(Channel.UNLIMITED)
        var rejectDown = false
        var dropControlResponses = false
        override suspend fun receive() = replies.receive()
        override suspend fun send(packet: PtpIpPacket) {
            packets += packet
            if (packet.type != PtpIpPacketType.OPERATION_REQUEST) return
            val id = packet.body[6].toInt() and 255
            val opcode =
                (packet.body[4].toInt() and 255) or ((packet.body[5].toInt() and 255) shl 8)
            if (opcode == 0x9209) {
                replies.trySend(PtpIpOperations.startData(id, properties.size))
                replies.trySend(PtpIpOperations.endData(id, properties))
            } else if (dropControlResponses) return
            val response = if (opcode == 0x9207 && rejectDown) 0x2019 else 0x2001
            replies.trySend(PtpIpPacket(7, number(response.toLong(), 2) + number(id.toLong(), 4)))
        }
    }

    private fun ready() = SonyPtpInitializationResult.Ready(
        "4.00", 310,
        SonyExtendedDeviceInfo(300, setOf(0xd12b, 0xd296), setOf(0xd308, 0xd2e8)),
        PtpDeviceInfo(setOf(0x9207, 0x9209)), null
    )

    @Test
    fun prefersDirectModeOffAndSendsExactlyOnePressRelease() = runTest {
        val transport = Transport(dataset(property(0xd12b), property(0xd296)))
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        assertEquals(WifiShutdownStatus.Requested, SonyWifiShutdown(queue, ready()).request())
        val controls = transport.packets.filter { it.type == 6 && it.body[4] == 7.toByte() }
        assertEquals(2, controls.size)
        controls.forEach {
            assertContentEquals(
                number(0xd308, 4) + number(1, 4),
                it.body.copyOfRange(10, it.body.size)
            )
        }
        assertEquals(
            listOf(2, 1),
            transport.packets.filter { it.type == 12 }.map { it.body[4].toInt() })
        queue.close()
    }

    @Test
    fun usesPowerOffWhenDirectModeOffIsDisabled() = runTest {
        val transport = Transport(dataset(property(0xd12b, value = 0), property(0xd296)))
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        assertEquals(WifiShutdownStatus.Requested, SonyWifiShutdown(queue, ready()).request())
        assertEquals(
            0xe8.toByte(),
            transport.packets.first { it.type == 6 && it.body[4] == 7.toByte() }.body[10]
        )
        queue.close()
    }

    @Test
    fun missingCapabilityOrDisabledPropertiesNeverSendShutdown() = runTest {
        val transport =
            Transport(dataset(property(0xd12b, enabled = 0), property(0xd296, value = 0)))
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val unsupported =
            ready().copy(extendedInfo = SonyExtendedDeviceInfo(300, emptySet(), emptySet()))
        assertEquals(WifiShutdownStatus.Unavailable, SonyWifiShutdown(queue, unsupported).request())
        assertTrue(transport.packets.isEmpty())
        assertEquals(WifiShutdownStatus.Unavailable, SonyWifiShutdown(queue, ready()).request())
        assertEquals(1, transport.packets.size)
        queue.close()
    }

    @Test
    fun rejectedOrLostResponseNeverRetriesShutdownOrClaimsWifiIsOff() = runTest {
        for (drop in listOf(false, true)) {
            val transport = Transport(dataset(property(0xd296))).apply {
                rejectDown = !drop; dropControlResponses = drop
            }
            val queue = PtpIpCommandQueue(transport, backgroundScope)
            assertEquals(WifiShutdownStatus.Unconfirmed, SonyWifiShutdown(queue, ready()).request())
            assertEquals(1, transport.packets.count { it.type == 6 && it.body[4] == 7.toByte() })
            queue.close()
        }
    }

    @Test
    fun parserSkipsUnusedStringsAndRejectsAmbiguousOrTruncatedData() {
        val opaque = number(0x5000, 2) + number(0xffff, 2) + byteArrayOf(0, 1, 1, 42, 42, 0, 0)
        assertEquals(
            setOf(0xd296),
            SonyWifiShutdownProperties.parse(dataset(opaque, property(0xd296)), emptySet())
        )
        assertFailsWith<IllegalArgumentException> {
            SonyWifiShutdownProperties.parse(
                dataset(property(0xd296), property(0xd296)),
                emptySet()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SonyWifiShutdownProperties.parse(
                dataset(property(0xd296)).dropLast(1).toByteArray(),
                emptySet()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SonyWifiShutdownProperties.parse(
                number(-1, 8),
                emptySet()
            )
        }
    }
}
