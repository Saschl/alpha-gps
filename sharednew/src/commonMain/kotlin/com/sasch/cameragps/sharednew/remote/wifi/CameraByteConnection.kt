package com.sasch.cameragps.sharednew.remote.wifi

internal interface CameraByteConnection {
    suspend fun write(bytes: ByteArray)
    suspend fun read(): ByteArray?
    suspend fun close()
}

internal class CameraPtpIpConnection(private val connection: CameraByteConnection) : PtpIpPacketConnection {
    private val decoder = PtpIpPacketCodec()
    private val pending = ArrayDeque<PtpIpPacket>()

    override suspend fun send(packet: PtpIpPacket) = connection.write(PtpIpPacketCodec.encode(packet))

    override suspend fun receive(): PtpIpPacket {
        while (pending.isEmpty()) {
            val bytes = connection.read() ?: error("PTP/IP connection closed")
            pending.addAll(decoder.feed(bytes))
        }
        return pending.removeFirst()
    }

    override suspend fun close() = connection.close()
}
