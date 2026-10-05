package com.sasch.cameragps.sharednew.remote.wifi

internal object PtpIpPacketType {
    const val OPERATION_REQUEST = 6
    const val OPERATION_RESPONSE = 7
    const val START_DATA = 9
    const val DATA = 10
    const val END_DATA = 12
}

/** Bounded PTP/IP data-in transfer, used for device information and Sony negotiation. */
internal class PtpIpDataIn(private val transactionId: Int, private val maxBytes: Int = 8 * 1024 * 1024) {
    private var expectedLength: Int? = null
    private var received = 0
    private var bytes: ByteArray? = null
    private var ended = false

    fun consume(packet: PtpIpPacket) {
        when (packet.type) {
            PtpIpPacketType.START_DATA -> {
                require(expectedLength == null && packet.body.size == 12)
                require(get32(packet.body, 0).toInt() == transactionId)
                val length = get32(packet.body, 4) or (get32(packet.body, 8) shl 32)
                require(length in 0..maxBytes.toLong())
                expectedLength = length.toInt()
                bytes = ByteArray(length.toInt())
            }
            PtpIpPacketType.DATA, PtpIpPacketType.END_DATA -> {
                require(expectedLength != null && !ended && packet.body.size >= 4)
                require(get32(packet.body, 0).toInt() == transactionId)
                val chunkSize = packet.body.size - 4
                require(chunkSize <= expectedLength!! - received)
                packet.body.copyInto(bytes!!, received, 4)
                received += chunkSize
                if (packet.type == PtpIpPacketType.END_DATA) {
                    require(received == expectedLength)
                    ended = true
                }
            }
            else -> throw IllegalArgumentException("Unexpected PTP/IP data packet")
        }
    }

    fun completedData(): ByteArray? {
        require(expectedLength == null || ended)
        return bytes
    }

    private fun get32(data: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, index -> value or ((data[offset + index].toLong() and 0xff) shl (index * 8)) }
}

internal fun PtpIpPacket.dataTransactionId(): Int {
    require(type in listOf(PtpIpPacketType.START_DATA, PtpIpPacketType.DATA, PtpIpPacketType.END_DATA))
    require(body.size >= 4)
    return (body[0].toInt() and 0xff) or
        ((body[1].toInt() and 0xff) shl 8) or
        ((body[2].toInt() and 0xff) shl 16) or
        ((body[3].toInt() and 0xff) shl 24)
}

internal data class PtpIpOperationResponse(
    val code: Int,
    val transactionId: Int,
    val parameters: List<Long>,
)

/** PTP/IP operation packets; Sony operation codes are supplied only after capability negotiation. */
internal object PtpIpOperations {
    fun request(code: Int, transactionId: Int, parameters: List<Long> = emptyList(),
                dataOut: Boolean = false): PtpIpPacket {
        require(code in 0..0xffff)
        require(transactionId > 0)
        require(parameters.size <= 5)
        require(parameters.all { it in 0..0xffffffffL })
        val body = ByteArray(10 + 4 * parameters.size)
        put32(body, 0, if (dataOut) 2 else 1)
        put16(body, 4, code)
        put32(body, 6, transactionId.toLong())
        parameters.forEachIndexed { index, parameter -> put32(body, 10 + index * 4, parameter) }
        return PtpIpPacket(PtpIpPacketType.OPERATION_REQUEST, body)
    }

    fun startData(transactionId: Int, byteCount: Int): PtpIpPacket {
        require(transactionId > 0 && byteCount in 0..PtpIpPacketCodec.MAX_PACKET_BYTES - 12)
        val body = ByteArray(12)
        put32(body, 0, transactionId.toLong())
        put32(body, 4, byteCount.toLong())
        return PtpIpPacket(PtpIpPacketType.START_DATA, body)
    }

    fun endData(transactionId: Int, data: ByteArray): PtpIpPacket {
        require(transactionId > 0 && data.size <= PtpIpPacketCodec.MAX_PACKET_BYTES - 12)
        val body = ByteArray(4 + data.size)
        put32(body, 0, transactionId.toLong())
        data.copyInto(body, 4)
        return PtpIpPacket(PtpIpPacketType.END_DATA, body)
    }

    fun response(packet: PtpIpPacket): PtpIpOperationResponse {
        require(packet.type == PtpIpPacketType.OPERATION_RESPONSE)
        require(packet.body.size in 6..26 && (packet.body.size - 6) % 4 == 0)
        return PtpIpOperationResponse(
            code = get16(packet.body, 0),
            transactionId = get32(packet.body, 2).toInt(),
            parameters = (6 until packet.body.size step 4).map { get32(packet.body, it) },
        )
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
