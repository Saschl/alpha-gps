package com.sasch.cameragps.sharednew.remote.wifi

/** The PTP/IP envelope shared by command and event connections. */
internal data class PtpIpPacket(val type: Int, val body: ByteArray)

/**
 * Incremental decoder: TCP may split a header or combine several packets in one read.
 * A length is checked before allocation so a bad camera/network packet cannot grow memory without bound.
 */
internal class PtpIpPacketCodec(private val maxPacketBytes: Int = MAX_PACKET_BYTES) {
    private val header = ByteArray(HEADER_BYTES)
    private var headerSize = 0
    private var frame: ByteArray? = null
    private var frameSize = 0

    init {
        require(maxPacketBytes >= HEADER_BYTES)
    }

    fun feed(bytes: ByteArray): List<PtpIpPacket> {
        if (bytes.isEmpty()) return emptyList()
        val packets = mutableListOf<PtpIpPacket>()
        var offset = 0
        while (offset < bytes.size) {
            if (frame == null) {
                val count = minOf(HEADER_BYTES - headerSize, bytes.size - offset)
                bytes.copyInto(header, headerSize, offset, offset + count)
                headerSize += count
                offset += count
                if (headerSize < HEADER_BYTES) break

                val length = readUInt32(header, 0)
                require(length in HEADER_BYTES.toLong()..maxPacketBytes.toLong()) {
                    "Invalid PTP/IP packet length: $length"
                }
                frame = ByteArray(length.toInt()).also { header.copyInto(it) }
                frameSize = HEADER_BYTES
                headerSize = 0
            }
            val target = frame!!
            val needed = target.size - frameSize
            val count = minOf(needed, bytes.size - offset)
            if (count > 0) {
                bytes.copyInto(target, frameSize, offset, offset + count)
                frameSize += count
                offset += count
            }
            if (frameSize == target.size) {
                packets += PtpIpPacket(readUInt32(target, 4).toInt(), target.copyOfRange(HEADER_BYTES, target.size))
                frame = null
                frameSize = 0
            }
        }
        return packets
    }

    /** Drop partial data whenever the underlying connection closes or changes. */
    fun reset() {
        headerSize = 0
        frame = null
        frameSize = 0
    }

    companion object {
        const val HEADER_BYTES = 8
        const val MAX_PACKET_BYTES = 8 * 1024 * 1024

        fun encode(packet: PtpIpPacket, maxPacketBytes: Int = MAX_PACKET_BYTES): ByteArray {
            val length = HEADER_BYTES.toLong() + packet.body.size
            require(length <= maxPacketBytes && length >= HEADER_BYTES)
            val result = ByteArray(length.toInt())
            writeUInt32(result, 0, length)
            writeUInt32(result, 4, packet.type.toLong() and 0xffffffffL)
            packet.body.copyInto(result, HEADER_BYTES)
            return result
        }

        private fun readUInt32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)

        private fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
            for (index in 0..3) bytes[offset + index] = (value ushr (index * 8)).toByte()
        }
    }
}
