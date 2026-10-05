package com.sasch.cameragps.sharednew.remote.wifi

internal data class PtpIpCommandAck(
    val connectionNumber: Long,
    val cameraGuid: ByteArray,
    val cameraName: String,
    val protocolVersion: Long,
)

/** PTP/IP connection setup shared by the command and event sockets. */
internal object PtpIpHandshake {
    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_ACK = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_ACK = 4
    const val INIT_FAIL = 5

    fun commandRequest(clientGuid: ByteArray, clientName: String): PtpIpPacket {
        require(clientGuid.size == 16)
        require(clientName.length + 1 <= 40)
        val body = ByteArray(16 + (clientName.length + 1) * 2 + 4)
        clientGuid.copyInto(body)
        clientName.forEachIndexed { index, char -> put16(body, 16 + index * 2, char.code) }
        put32(body, body.size - 4, 0x00010000)
        return PtpIpPacket(INIT_COMMAND_REQUEST, body)
    }

    fun parseCommandAck(packet: PtpIpPacket): PtpIpCommandAck {
        require(packet.type == INIT_COMMAND_ACK)
        val body = packet.body
        require(body.size >= 26 && body.size % 2 == 0)
        val connectionNumber = get32(body, 0)
        val nameEnd = body.size - 4
        val name = StringBuilder()
        var offset = 20
        var terminated = false
        while (offset + 1 < nameEnd) {
            val code = get16(body, offset)
            offset += 2
            if (code == 0) {
                terminated = true
                break
            }
            require(name.length < 39)
            name.append(code.toChar())
        }
        require(terminated && offset == nameEnd)
        return PtpIpCommandAck(connectionNumber, body.copyOfRange(4, 20), name.toString(), get32(body, nameEnd))
    }

    fun eventRequest(connectionNumber: Long): PtpIpPacket {
        require(connectionNumber in 0..0xffffffffL)
        return PtpIpPacket(INIT_EVENT_REQUEST, ByteArray(4).also { put32(it, 0, connectionNumber) })
    }

    private fun get16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun get32(data: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, index -> value or ((data[offset + index].toLong() and 0xff) shl (index * 8)) }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Long) {
        for (index in 0..3) data[offset + index] = (value ushr (index * 8)).toByte()
    }
}
