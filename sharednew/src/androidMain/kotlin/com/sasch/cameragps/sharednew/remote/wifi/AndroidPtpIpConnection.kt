package com.sasch.cameragps.sharednew.remote.wifi

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class AndroidPtpIpConnectionFactory(
    private val network: Network,
    private val host: String,
    private val port: Int = 15740,
) : PtpIpConnectionFactory {
    init { require(port in 1..65535) }

    override suspend fun connect(): PtpIpPacketConnection =
        CameraPtpIpConnection(AndroidCameraByteConnection.open(network.socketFactory, host, port))
}

internal class AndroidPtpIpConnection(socket: Socket) : PtpIpPacketConnection by
    CameraPtpIpConnection(AndroidCameraByteConnection(socket))

internal class AndroidCameraByteConnection(private val socket: Socket) : CameraByteConnection {
    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        socket.getOutputStream().write(bytes)
    }

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = try { socket.getInputStream().read(buffer) }
            catch (_: SocketTimeoutException) { continue }
            return@withContext if (count < 0) null else buffer.copyOf(count)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) { socket.close() }

    companion object {
        suspend fun open(sockets: SocketFactory, host: String, port: Int): AndroidCameraByteConnection {
            var socket: Socket? = null
            try {
                return withContext(Dispatchers.IO) {
                    val opened = sockets.createSocket()
                    socket = opened
                    opened.connect(InetSocketAddress(host, port), 10_000)
                    opened.soTimeout = 1_000
                    AndroidCameraByteConnection(opened)
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { socket?.close() }
                throw failure
            }
        }
    }
}
