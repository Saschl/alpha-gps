package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SonyPtpShutterTest {
    private class Transport : PtpIpCommandTransport {
        val sent = mutableListOf<PtpIpPacket>()
        val replies = Channel<PtpIpPacket>(Channel.UNLIMITED)
        var onSend: ((PtpIpPacket) -> Unit)? = null
        override suspend fun send(packet: PtpIpPacket) { sent += packet; onSend?.invoke(packet) }
        override suspend fun receive(): PtpIpPacket = replies.receive()
        fun response(id: Int, code: Int = 0x2001) {
            replies.trySend(PtpIpPacket(7, byteArrayOf(code.toByte(), (code ushr 8).toByte(),
                id.toByte(), 0, 0, 0)))
        }
    }

    private fun ready(): SonyPtpInitializationResult.Ready = SonyPtpInitializationResult.Ready(
        serverVersion = "4.00", vendorCodeVersion = 310,
        extendedInfo = SonyExtendedDeviceInfo(1, emptySet(), setOf(0xd2c1, 0xd2c2)),
        deviceInfo = PtpDeviceInfo(setOf(SonyPtpOperation.SDIO_CONTROL_DEVICE)),
        deviceDescription = null,
    )

    @Test
    fun sendsOneFullPressAndReleasesInReverseOrder() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val shutter = SonyPtpShutter(queue, ready())
        (1..4).forEach(transport::response)
        val result = async { shutter.captureStill() }
        runCurrent()
        assertEquals(3, transport.sent.size)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(SonyPtpCaptureResult.Submitted, result.await())
        assertEquals(12, transport.sent.size)
        assertEquals(listOf(0xd2c1, 0xd2c2, 0xd2c2, 0xd2c1),
            transport.sent.filter { it.type == 6 }.map { packet ->
                val body = packet.body
                (body[10].toInt() and 0xff) or ((body[11].toInt() and 0xff) shl 8)
            })
        assertEquals(listOf(2, 2, 1, 1), transport.sent.filter { it.type == 12 }
            .map { it.body[4].toInt() })
        queue.close()
    }

    @Test
    fun rejectsUnsupportedControlsWithoutSendingAnything() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val noFullPress = ready().copy(extendedInfo = SonyExtendedDeviceInfo(1, emptySet(), setOf(0xd2c1)))
        assertEquals(SonyPtpCaptureResult.Unsupported, SonyPtpShutter(queue, noFullPress).captureStill())
        assertTrue(transport.sent.isEmpty())
        queue.close()
    }

    @Test
    fun lostFullReleaseStillSendsHalfReleaseWithoutRetryingFullPress() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val shutter = SonyPtpShutter(queue, ready())
        transport.response(1)
        transport.response(2)
        val result = async { shutter.captureStill() }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(9, transport.sent.size)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(12, transport.sent.size)
        transport.response(4)
        runCurrent()
        assertEquals(SonyPtpCaptureResult.Uncertain, result.await())
        assertEquals(listOf(2, 2, 1, 1), transport.sent.filter { it.type == 12 }
            .map { it.body[4].toInt() })
        queue.close()
    }

    @Test
    fun subscribesBeforeFullPressAndReportsCaptureEvent() = runTest {
        val transport = Transport()
        val eventConnection = TestEventConnection()
        val monitor = PtpIpEventMonitor(eventConnection, backgroundScope)
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val shutter = SonyPtpShutter(queue, ready(), monitor)
        transport.onSend = { packet ->
            if (packet.type == 6 && packet.body[6].toInt() == 2) eventConnection.captured()
        }
        (1..4).forEach(transport::response)
        val result = async { shutter.captureStill() }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(SonyPtpCaptureResult.CaptureEventObserved, result.await())
        queue.close()
        monitor.close()
    }

    @Test
    fun cancellationDuringHalfPressReleasesWithoutTakingPhoto() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val shutter = SonyPtpShutter(queue, ready())
        transport.response(1)
        transport.response(2)
        val result = async { shutter.captureStill() }
        runCurrent()
        assertEquals(SonyPtpCaptureResult.Busy, shutter.captureStill())
        result.cancel()
        runCurrent()
        result.join()
        assertEquals(listOf(2, 1), transport.sent.filter { it.type == 12 }.map { it.body[4].toInt() })
        assertTrue(transport.sent.filter { it.type == 6 }.all { it.body[10] == 0xc1.toByte() })
        queue.close()
    }
}
