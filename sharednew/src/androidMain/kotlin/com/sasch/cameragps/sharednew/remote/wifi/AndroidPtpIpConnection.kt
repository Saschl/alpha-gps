package com.sasch.cameragps.sharednew.remote.wifi

import android.net.Network
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A socket created by the approved camera Network; the process routing is untouched. */
internal class AndroidPtpIpConnectionFactory(
    private val network: Network,
    private val host: String,
    private val port: Int = 15740,
) : PtpIpConnectionFactory {
    init {
        require(port in 1..65535)
    }

    override suspend fun connect(): PtpIpPacketConnection {
        var socket: Socket? = null
        try {
            return withContext(Dispatchers.IO) {
                val opened = network.socketFactory.createSocket()
                socket = opened
                opened.connect(InetSocketAddress(host, port), 10_000)
                opened.soTimeout = 1_000
                AndroidPtpIpConnection(opened)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { socket?.close() }
            throw failure
        }
    }
}

internal class AndroidPtpIpConnection(private val socket: Socket) : PtpIpPacketConnection {
    private val decoder = PtpIpPacketCodec()
    private val pending = ArrayDeque<PtpIpPacket>()
    private val readBuffer = ByteArray(16 * 1024)

    override suspend fun send(packet: PtpIpPacket) = withContext(Dispatchers.IO) {
        socket.getOutputStream().write(PtpIpPacketCodec.encode(packet))
    }

    override suspend fun receive(): PtpIpPacket = withContext(Dispatchers.IO) {
        while (pending.isEmpty()) {
            currentCoroutineContext().ensureActive()
            val bytes = try {
                socket.getInputStream().read(readBuffer)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (bytes < 0) throw EOFException("PTP/IP connection closed")
            if (bytes > 0) pending.addAll(decoder.feed(readBuffer.copyOf(bytes)))
        }
        pending.removeFirst()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        socket.close()
    }
}
