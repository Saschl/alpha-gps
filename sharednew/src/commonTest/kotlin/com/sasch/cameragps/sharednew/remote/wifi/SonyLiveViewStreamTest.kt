package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SonyLiveViewStreamTest {
    private class Camera : PtpIpCommandTransport {
        val events = TestEventConnection()
        val responses = Channel<PtpIpPacket>(Channel.UNLIMITED)
        val controls = mutableListOf<Pair<Int, Int>>()
        var code = 0
        var rejectEnable = false
        override suspend fun send(packet: PtpIpPacket) {
            if (packet.type == 6) code = (packet.body[10].toInt() and 0xff) or
                ((packet.body[11].toInt() and 0xff) shl 8)
            if (packet.type == 12) {
                val value = packet.body[4].toInt()
                controls += code to value
                if (code == 0xd312 && value == 2) events.packets.trySend(PtpIpPacket(8,
                    byteArrayOf(0x22, 0xc2.toByte(), 0, 0, 0, 0, 0x12, 0xd3.toByte(), 7, 0x92.toByte(), 1, 0, 0, 0)))
                val response = if (rejectEnable && code == 0xd313 && value == 2) 0x2019 else 0x2001
                responses.trySend(PtpIpPacket(7, byteArrayOf(response.toByte(), (response ushr 8).toByte()) +
                    packet.body.copyOfRange(0, 4)))
            }
        }
        override suspend fun receive() = responses.receive()
    }

    private class Stream : SonyLiveViewByteStream {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        var closed = false
        override suspend fun read() = chunks.receiveCatching().getOrNull()
        override suspend fun close() { closed = true }
        fun frame() {
            val jpeg = testJpeg()
            chunks.trySend(byteArrayOf(16, 0, 0, 0, (jpeg.size + 34).toByte(), 0, 0, 0) +
                ByteArray(8) + jpeg + ByteArray(34))
        }
    }

    private class Fixture(scope: TestScope, http: SonyLiveViewHttpTransport) {
        val camera = Camera()
        val queue = PtpIpCommandQueue(camera, scope.backgroundScope)
        val monitor = PtpIpEventMonitor(camera.events, scope.backgroundScope)
        val preview = SonyLiveViewStream(queue, SonyPtpInitializationResult.Ready("4.00", 310,
            SonyExtendedDeviceInfo(300, emptySet(), setOf(0xd312, 0xd313)),
            PtpDeviceInfo(setOf(SonyPtpOperation.SDIO_CONTROL_DEVICE)), null), monitor, http,
            StandardTestDispatcher(scope.testScheduler))
        fun close() { queue.close(); monitor.close() }
    }

    private val endpoint = SonyLiveViewEndpoint.fromUrl("http://127.0.0.1/live", "127.0.0.1")

    @Test
    fun retriesOnlyHttpAndReleasesControlsAfterReceivingFrame() = runTest {
        val stream = Stream().apply { frame() }
        var attempts = 0
        val fixture = Fixture(this) {
            attempts++
            if (attempts == 1) throw SonyLiveViewHttpStatus(503)
            stream
        }
        val frame = fixture.preview.frames(endpoint).first()
        assertContentEquals(testJpeg(), frame.imageBytes)
        assertEquals(2, attempts)
        assertTrue(stream.closed)
        assertEquals(listOf(0xd312 to 2, 0xd313 to 2, 0xd313 to 1, 0xd312 to 1), fixture.camera.controls)
        fixture.close()
    }

    @Test
    fun eventLossClosesHttpAndReleasesControls() = runTest {
        val stream = Stream()
        val fixture = Fixture(this) { stream }
        val outcome = async { runCatching { fixture.preview.frames(endpoint).first() } }
        runCurrent()
        fixture.camera.events.close()
        runCurrent()
        assertTrue(outcome.await().isFailure)
        assertTrue(stream.closed)
        assertEquals(listOf(0xd313 to 1, 0xd312 to 1), fixture.camera.controls.takeLast(2))
        fixture.close()
    }

    @Test
    fun rejectedEnableDoesNotOpenHttpAndStillDisablesControls() = runTest {
        var opens = 0
        val fixture = Fixture(this) { opens++; Stream() }
        fixture.camera.rejectEnable = true
        assertFailsWith<IllegalStateException> { fixture.preview.frames(endpoint).first() }
        assertEquals(0, opens)
        assertEquals(listOf(0xd313 to 1, 0xd312 to 1), fixture.camera.controls.takeLast(2))
        fixture.close()
    }

    @Test
    fun cancellationWhileWaitingForFrameCleansUpAndAllowsRestart() = runTest {
        val stream = Stream()
        val fixture = Fixture(this) { stream }
        val reading = async { fixture.preview.frames(endpoint).first() }
        runCurrent()
        reading.cancel()
        runCurrent()
        reading.join()
        assertTrue(stream.closed)
        stream.frame()
        assertEquals(640, fixture.preview.frames(endpoint).first().width)
        fixture.close()
    }

    @Test
    fun partialFrameCannotKeepPreviewAliveIndefinitely() = runTest {
        val stream = Stream().apply { chunks.trySend(byteArrayOf(16)) }
        val fixture = Fixture(this) { stream }
        val reading = async { runCatching { fixture.preview.frames(endpoint).first() } }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(reading.await().isFailure)
        assertTrue(stream.closed)
        fixture.close()
    }
}
