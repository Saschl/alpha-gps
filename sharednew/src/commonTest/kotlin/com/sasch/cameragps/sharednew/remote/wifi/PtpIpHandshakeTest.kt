package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PtpIpHandshakeTest {
    @Test
    fun encodesClientIdentityAndVersionAndParsesCameraAck() {
        val guid = ByteArray(16) { it.toByte() }
        val request = PtpIpHandshake.commandRequest(guid, "AlphaGPS")
        assertEquals(PtpIpHandshake.INIT_COMMAND_REQUEST, request.type)
        assertContentEquals(guid, request.body.copyOfRange(0, 16))
        assertContentEquals(byteArrayOf(0x41, 0, 0x6c, 0), request.body.copyOfRange(16, 20))
        assertContentEquals(byteArrayOf(0, 0, 1, 0), request.body.copyOfRange(request.body.size - 4, request.body.size))

        val ack = PtpIpPacket(PtpIpHandshake.INIT_COMMAND_ACK,
            byteArrayOf(7, 0, 0, 0) + guid + byteArrayOf(0x41, 0, 0, 0, 0, 0, 1, 0))
        val parsed = PtpIpHandshake.parseCommandAck(ack)
        assertEquals(7, parsed.connectionNumber)
        assertContentEquals(guid, parsed.cameraGuid)
        assertEquals("A", parsed.cameraName)
        assertEquals(0x10000, parsed.protocolVersion)
        assertContentEquals(byteArrayOf(7, 0, 0, 0), PtpIpHandshake.eventRequest(parsed.connectionNumber).body)
    }

    @Test
    fun rejectsUnterminatedCameraName() {
        val ack = PtpIpPacket(PtpIpHandshake.INIT_COMMAND_ACK,
            byteArrayOf(7, 0, 0, 0) + ByteArray(16) + byteArrayOf(0x41, 0, 0, 0, 1, 0))
        assertFailsWith<IllegalArgumentException> { PtpIpHandshake.parseCommandAck(ack) }
    }
}
