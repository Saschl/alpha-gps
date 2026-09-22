package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PtpIpPacketCodecTest {
    @Test
    fun fragmentedHeaderAndBodyThenCombinedPackets() {
        val decoder = PtpIpPacketCodec()
        val first = PtpIpPacket(6, byteArrayOf(1, 2, 3))
        val second = PtpIpPacket(7, byteArrayOf(4, 5))
        val encoded = PtpIpPacketCodec.encode(first) + PtpIpPacketCodec.encode(second)

        assertTrue(decoder.feed(encoded.copyOfRange(0, 3)).isEmpty())
        assertTrue(decoder.feed(encoded.copyOfRange(3, 9)).isEmpty())
        val result = decoder.feed(encoded.copyOfRange(9, encoded.size))
        assertEquals(listOf(6, 7), result.map { it.type })
        assertContentEquals(first.body, result[0].body)
        assertContentEquals(second.body, result[1].body)
    }

    @Test
    fun rejectsMalformedAndOversizedLengthsBeforeBufferingBody() {
        val decoder = PtpIpPacketCodec(maxPacketBytes = 16)
        assertFailsWith<IllegalArgumentException> {
            decoder.feed(byteArrayOf(7, 0, 0, 0, 1, 0, 0, 0))
        }
        decoder.reset()
        assertFailsWith<IllegalArgumentException> {
            decoder.feed(byteArrayOf(17, 0, 0, 0, 1, 0, 0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            PtpIpPacketCodec.encode(PtpIpPacket(1, ByteArray(9)), maxPacketBytes = 16)
        }
    }

    @Test
    fun resetDiscardsOldConnectionFragment() {
        val decoder = PtpIpPacketCodec()
        decoder.feed(byteArrayOf(10, 0, 0))
        decoder.reset()
        val packet = PtpIpPacket(2, byteArrayOf(9))
        val result = decoder.feed(PtpIpPacketCodec.encode(packet))
        assertEquals(1, result.size)
        assertEquals(2, result.single().type)
        assertContentEquals(packet.body, result.single().body)
    }

    @Test
    fun operationRequestMatchesApkObservedLittleEndianLayout() {
        val packet = PtpIpOperations.request(0x1002, 1, listOf(1))
        assertContentEquals(
            byteArrayOf(
                22, 0, 0, 0, // total packet length
                6, 0, 0, 0, // operation request
                1, 0, 0, 0, // no-data or data-in phase
                2, 0x10, // operation code
                1, 0, 0, 0, // transaction ID
                1, 0, 0, 0, // parameter
            ),
            PtpIpPacketCodec.encode(packet),
        )
    }

    @Test
    fun acceptsOneByteReadsAndHeaderOnlyPackets() {
        val decoder = PtpIpPacketCodec()
        val encoded = PtpIpPacketCodec.encode(PtpIpPacket(4, byteArrayOf()))
        val packets = encoded.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(1, packets.size)
        assertEquals(4, packets.single().type)
        assertTrue(packets.single().body.isEmpty())
    }
}
