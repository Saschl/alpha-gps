@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.Network.*
import platform.posix.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class IosCameraByteConnectionTest : WifiLoggingTest() {
    @Test fun sendsMultipleMessagesAndReceivesBytesAndEofOverPlainTcp() = onMainRunLoop {
        val listener = socket(AF_INET, SOCK_STREAM, 0)
        check(listener >= 0)
        try {
            val port = memScoped {
                val address = alloc<sockaddr_in>()
                memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
                address.sin_len = sizeOf<sockaddr_in>().convert()
                address.sin_family = AF_INET.convert()
                address.sin_port = 0u
                address.sin_addr.ptr.reinterpret<UByteVar>()[0] = 127u
                address.sin_addr.ptr.reinterpret<UByteVar>()[3] = 1u
                check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0)
                check(listen(listener, 1) == 0)
                val size = alloc<socklen_tVar> { value = sizeOf<sockaddr_in>().convert() }
                check(getsockname(listener, address.ptr.reinterpret(), size.ptr) == 0)
                // Darwin stores this field in network byte order.
                val raw = address.ptr.reinterpret<UByteVar>()
                raw[2].toInt() * 256 + raw[3].toInt()
            }
            val first = byteArrayOf(12, 0, 0, 0, 1, 0, 0, 0, -1, 0, 1, 2)
            val second = ByteArray(32_000) { it.toByte() }
            val expected = first + second
            val peer = async(Dispatchers.Default) {
                memScoped {
                    val ready = alloc<pollfd> { fd = listener; events = POLLIN.convert() }
                    check(poll(ready.ptr, 1u, 10_000) == 1) { "Client did not connect" }
                }
                val socket = accept(listener, null, null)
                check(socket >= 0)
                try {
                    memScoped {
                        val timeout = alloc<timeval> { tv_sec = 10; tv_usec = 0 }
                        check(setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert()) == 0)
                        check(setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().convert()) == 0)
                    }
                    val received = ByteArray(expected.size)
                    received.usePinned { pinned ->
                        var offset = 0
                        while (offset < received.size) {
                            val count = recv(socket, pinned.addressOf(offset), (received.size - offset).convert(), 0).toInt()
                            check(count > 0) { "Client closed or failed before both messages arrived" }
                            offset += count
                        }
                    }
                    assertContentEquals(expected, received)
                    received.usePinned { pinned ->
                        var offset = 0
                        while (offset < received.size) {
                            val count = send(socket, pinned.addressOf(offset), (received.size - offset).convert(), 0).toInt()
                            check(count > 0)
                            offset += count
                        }
                    }
                    shutdown(socket, SHUT_WR)
                } finally {
                    close(socket)
                }
            }
            val parameters = iosCameraTcpParameters().also {
                nw_parameters_set_required_interface_type(it, nw_interface_type_other)
            }
            val connection = IosCameraByteConnection.open("127.0.0.1", port, parameters)
            try {
                connection.write(first)
                connection.write(second)
                val received = ArrayList<Byte>()
                while (true) {
                    val bytes = connection.read() ?: break
                    received.addAll(bytes.toList())
                }
                assertContentEquals(expected, received.toByteArray())
                assertNull(connection.read(), "EOF must remain terminal after the final bytes")
                peer.await()
            } finally {
                connection.close()
            }
        } finally {
            close(listener)
        }
    }

    private fun onMainRunLoop(block: suspend CoroutineScope.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val result = scope.async { withTimeout(20.seconds, block) }
        try {
            while (!result.isCompleted) {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            }
            runBlocking { result.await() }
        } finally {
            scope.cancel()
        }
    }
}
