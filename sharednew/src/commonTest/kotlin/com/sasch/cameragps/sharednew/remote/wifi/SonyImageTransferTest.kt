package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SonyImageTransferTest {
    private fun number(value: Long, size: Int) = ByteArray(size) { (value ushr (8 * it)).toByte() }
    private fun string(value: String) = byteArrayOf((value.length + 1).toByte()) +
            value.fold(byteArrayOf()) { bytes, char ->
                bytes + number(
                    char.code.toLong(),
                    2
                )
            } + byteArrayOf(0, 0)

    private fun info(size: Long, name: String = "DSC01234.JPG", format: Int = 0x3801,
                     storage: Long = 1, parent: Long = 0): ByteArray =
        (number(storage, 4) + number(format.toLong(), 2) + number(0, 2) + number(size, 4) +
                ByteArray(40) + string(name) + byteArrayOf(0, 0, 0))
            .also { number(parent, 4).copyInto(it, 38) }

    private fun properties(prepared: Boolean): ByteArray = number(1, 8) + number(0xd295, 2) +
            number(4, 2) + byteArrayOf(0, 1) + number(0, 2) + number(
        if (prepared) 1 else 0,
        2
    ) + byteArrayOf(0)

    private fun ready() = SonyPtpInitializationResult.Ready(
        "4.00", 310,
        SonyExtendedDeviceInfo(300, setOf(0xd295), emptySet()),
        PtpDeviceInfo(setOf(0x1004, 0x1007, 0x1008, 0x9211, 0x9212, 0x9209)), null
    )

    private inner class Transport(val size: Int = 600_000) : PtpIpCommandTransport {
        val replies = Channel<PtpIpPacket>(Channel.UNLIMITED)
        val operations = mutableListOf<Pair<Int, List<Long>>>()
        var objectCount = 1
        var metadata = emptyMap<Long, ByteArray>()
        var readyAfterPolls = 0
        var polls = 0
        var shortChunk = false
        var oversizedChunk = false
        var rejected = false
        override suspend fun receive() = replies.receive()
        override suspend fun send(packet: PtpIpPacket) {
            if (packet.type != 6) return
            fun read(offset: Int, size: Int) = (0 until size).fold(0L) { n, i ->
                n or ((packet.body[offset + i].toLong() and 255) shl (8 * i))
            }

            val id = read(6, 4).toInt()
            val code = read(4, 2).toInt()
            val params = (10 until packet.body.size step 4).map { read(it, 4) }
            operations += code to params
            val data = when (code) {
                0x9209 -> properties(polls++ >= readyAfterPolls)
                0x1004 -> number(1, 4) + number(0x10001, 4)
                0x1007 -> number(
                    objectCount.toLong(),
                    4
                ) + (1..objectCount).fold(byteArrayOf()) { bytes, id ->
                    bytes + number(
                        id.toLong(),
                        4
                    )
                }

                0x1008 -> metadata[params.first()] ?: info(size.toLong(), "DSC${params.first()}.JPG")
                0x101b, 0x9211 -> {
                    val count = params.last()
                        .toInt() - (if (shortChunk) 1 else 0) + (if (oversizedChunk) 1 else 0)
                    ByteArray(count) { ((params[1] + it) % 251).toByte() }
                }

                else -> null
            }
            if (data != null && !rejected) {
                replies.send(PtpIpOperations.startData(id, data.size))
                replies.send(PtpIpOperations.endData(id, data))
            }
            replies.send(
                PtpIpPacket(
                    7, number(if (rejected) 0x2019 else 0x2001, 2) + number(id.toLong(), 4) +
                            if (code == 0x101b) number(data!!.size.toLong(), 4) else byteArrayOf()
                )
            )
        }
    }

    private class Destination : CameraImageDestination {
        var received = 0
        var committed = false
        var aborted = false
        var blockWrite = false
        var failWrite = false
        var failCommit = false
        override suspend fun write(bytes: ByteArray) {
            if (blockWrite) awaitCancellation()
            check(!failWrite)
            bytes.forEachIndexed { index, byte ->
                assertEquals(
                    ((received + index) % 251).toByte(),
                    byte
                )
            }
            received += bytes.size
        }

        override suspend fun commit() {
            check(!failCommit); committed = true
        }

        override suspend fun abort() {
            aborted = true
        }
    }

    @Test
    fun browsesPagesAndDownloadsSelectedOriginalInBoundedChunks() = runTest {
        val transport = Transport().apply { objectCount = SonyImageTransfer.PAGE_SIZE + 5; readyAfterPolls = 2 }
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val outputs = mutableListOf<Destination>()
        val progress = mutableListOf<WifiImageTransferState>()
        val transfer = SonyImageTransfer(queue, ready()) { Destination().also { outputs += it } }
        val first = transfer.openBrowser()
        assertEquals(transport.objectCount, first.totalObjects)
        assertEquals((transport.objectCount.toLong() downTo 6L).toList(), first.photos.map { it.handle })
        val second = transfer.page(SonyImageTransfer.PAGE_SIZE)
        assertEquals((5L downTo 1L).toList(), second.photos.map { it.handle })
        val result = transfer.download(4L) { progress += it }
        assertEquals(WifiImageTransferStatus.Saved, result.status)
        assertTrue(
            outputs.single().let { it.committed && !it.aborted && it.received == transport.size })
        val reads = transport.operations.filter { it.first == 0x9211 }.map { it.second }
        assertEquals(listOf(listOf(4L, 0L, 0L, 524288L), listOf(4L, 524288L, 0L, 75712L)), reads)
        assertEquals(0x9212 to listOf(2L, 1L, 0L), transport.operations.first())
        assertEquals(
            listOf(0x10001L, 0L, 0L),
            transport.operations.first { it.first == 0x1007 }.second
        )
        assertEquals(listOf(0L, 1L), transport.operations.first { it.first == 0x9209 }.second)
        assertEquals(600_000L, progress.last().bytesReceived)
        transfer.closeBrowser()
        assertEquals(0x9212 to listOf(2L, 0L, 0L), transport.operations.last())
        queue.close()
    }

    @Test
    fun legacyPairsStayTogetherAcrossPagesAndIdenticalNamesInOtherFoldersStaySeparate() = runTest {
        val transport = Transport().apply {
            objectCount = (SonyImageTransfer.PAGE_SIZE + 1) * 2
            metadata = (1L..objectCount.toLong()).associateWith { handle ->
                val name = "DSC${(handle + 1) / 2}"
                if (handle % 2 == 0L) info(size.toLong(), "$name.ARW", 0xb101)
                else info(size.toLong(), "$name.JPG")
            }
        }
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val transfer = SonyImageTransfer(queue, ready()) { Destination() }
        val first = transfer.openBrowser()
        assertEquals(SonyImageTransfer.PAGE_SIZE + 1, first.totalObjects)
        assertEquals(SonyImageTransfer.PAGE_SIZE * 2, first.photos.size)
        val last = transfer.page(SonyImageTransfer.PAGE_SIZE)
        assertEquals(listOf(2L, 1L), last.photos.map { it.handle })
        assertEquals(WifiImageTransferStatus.Saved, transfer.download(1) {}.status)
        assertEquals(WifiImageTransferStatus.Saved, transfer.download(2) {}.status)
        val jpg = SonyImageInfo.parse(info(1, "DSC.JPG", parent = 10))
        val raw = SonyImageInfo.parse(info(1, "DSC.ARW", 0xb101, parent = 10))
        val otherFolder = SonyImageInfo.parse(info(1, "DSC.ARW", 0xb101, parent = 11))
        val otherCard = SonyImageInfo.parse(info(1, "DSC.ARW", 0xb101, storage = 2, parent = 10))
        assertEquals(jpg.captureId, raw.captureId)
        assertTrue(jpg.captureId != otherFolder.captureId)
        assertTrue(jpg.captureId != otherCard.captureId)
        queue.close()
    }

    @Test
    fun invalidChunksAreNotPublishedOrRetried() = runTest {
        for (oversized in listOf(false, true)) {
            val transport =
                Transport().apply { shortChunk = !oversized; oversizedChunk = oversized }
            val queue = PtpIpCommandQueue(transport, backgroundScope)
            val output = Destination()
            val transfer = SonyImageTransfer(queue, ready()) { output }
            transfer.openBrowser()
            val result = transfer.download(1L) {}
            assertEquals(WifiImageTransferStatus.Failed, result.status)
            assertTrue(output.aborted)
            assertFalse(output.committed)
            assertEquals(1, transport.operations.count { it.first == 0x9211 })
            queue.close()
        }
    }

    @Test
    fun cancellationRemovesPartialFileWithoutChangingCameraFiles() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val output = Destination().apply { blockWrite = true }
        val transfer = SonyImageTransfer(queue, ready()) { output }
        transfer.openBrowser()
        val job = launch { transfer.download(1L) {} }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(output.aborted)
        assertFalse(output.committed)
        assertEquals(0x9211, transport.operations.last().first)
        queue.close()
    }

    @Test
    fun storageFailureNeverClaimsSuccessOrRetriesAnImage() = runTest {
        for (commitFailure in listOf(false, true)) {
            val transport = Transport()
            val queue = PtpIpCommandQueue(transport, backgroundScope)
            val output =
                Destination().apply { failWrite = !commitFailure; failCommit = commitFailure }
            val transfer = SonyImageTransfer(queue, ready()) { output }
            transfer.openBrowser()
            val result = transfer.download(1L) {}
            assertEquals(WifiImageTransferStatus.Failed, result.status)
            assertTrue(output.aborted)
            assertFalse(output.committed)
            queue.close()
        }
    }

    @Test
    fun unsupportedCamerasSendNothingAndOnlyListedHandlesCanBeDownloaded() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val unsupported = SonyImageTransfer(
            queue,
            ready().copy(deviceInfo = PtpDeviceInfo(emptySet()))
        ) { error("No file") }
        assertFalse(unsupported.supported)
        assertFailsWith<IllegalStateException> { unsupported.openBrowser() }
        assertTrue(transport.operations.isEmpty())
        val transfer = SonyImageTransfer(queue, ready()) { error("No file") }
        transfer.openBrowser()
        val before = transport.operations.size
        assertEquals(WifiImageTransferStatus.Failed, transfer.download(999L) {}.status)
        assertEquals(before, transport.operations.size)
        queue.close()
    }

    @Test
    fun standardPartialObjectFallbackUses32BitOffsetsAndChecksReturnedLength() = runTest {
        val transport = Transport()
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val capabilities =
            ready().copy(deviceInfo = PtpDeviceInfo(ready().deviceInfo.supportedOperations - 0x9211 + 0x101b))
        val transfer = SonyImageTransfer(queue, capabilities) { Destination() }
        transfer.openBrowser()
        assertEquals(WifiImageTransferStatus.Saved, transfer.download(1L) {}.status)
        assertEquals(
            listOf(1L, 0L, 524288L),
            transport.operations.first { it.first == 0x101b }.second
        )
        queue.close()
    }

    @Test
    fun rejectsMalformedHandleLists() {
        assertFailsWith<IllegalArgumentException> {
            SonyImageTransfer.parseIds(
                number(100001, 4),
                100000
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SonyImageTransfer.parseIds(
                number(
                    2,
                    4
                ) + number(1, 4), 100000
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SonyImageTransfer.parseIds(
                number(
                    1,
                    4
                ) + number(0, 4), 100000
            )
        }
    }

    @Test
    fun parsesSupportedImageMetadataAndRejectsUnsafeOrTruncatedNames() {
        assertEquals(SonyImageInfo(42, "DSC01234.JPG", "image/jpeg", captureId = "object:1:0:DSC01234:"), SonyImageInfo.parse(info(42)))
        assertEquals("image/x-sony-arw", SonyImageInfo.parse(info(42, "DSC.ARW", 0xb101)).mimeType)
        assertEquals("image/heif", SonyImageInfo.parse(info(42, "DSC.HIF", 0xb110)).mimeType)
        for (name in listOf("../a.jpg", "a/b.jpg", "a\\b.jpg", "", "\u0000x.jpg")) {
            assertFailsWith<IllegalArgumentException> { SonyImageInfo.parse(info(42, name)) }
        }
        assertFailsWith<IllegalArgumentException> {
            SonyImageInfo.parse(
                info(42).dropLast(1).toByteArray()
            )
        }
    }
}
