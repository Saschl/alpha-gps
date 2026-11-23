package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SonyContentCatalogTest : WifiLoggingTest() {
    private fun number(value: Long, size: Int) = ByteArray(size) { (value ushr (8 * it)).toByte() }
    private fun file(id: Int, format: Int, path: String): ByteArray {
        val name = path.encodeToByteArray() + byteArrayOf(0)
        return number(id.toLong(), 2) + ByteArray(2) + number(name.size.toLong(), 4) + name +
                number(format.toLong(), 4) + number(600_000, 8) + ByteArray(32) +
                number(1, 4) + number(6000, 4) + number(4000, 4) + number(0, 4) + number(0, 4)
    }

    private fun content(id: Int, time: Long, vararg files: ByteArray, localTime: Long = time): ByteArray =
        number(1, 4) + number(id.toLong(), 4) + ByteArray(20) + number(time, 8) + number(3000, 8) +
                number(localTime, 8) + number(3000, 8) + ByteArray(12) + number(
            0,
            4
        ) + number(files.size.toLong(), 4) +
                files.fold(byteArrayOf()) { bytes, file -> bytes + file }

    private fun batch(vararg records: ByteArray, identity: Byte = 1) =
        number(1, 2) + ByteArray(2) + number(0, 8) +
                ByteArray(128) { identity } + number(1, 4) + number(records.size.toLong(), 4) +
                records.fold(byteArrayOf()) { bytes, record -> bytes + record }

    private val jpeg get() = file(1, 0x3801, "DCIM/100MSDCF/DSC00001.JPG")
    private val raw get() = file(2, 0xb101, "DCIM/100MSDCF/DSC00001.ARW")
    private val heif get() = file(3, 0xb110, "DCIM/100MSDCF/DSC00001.HIF")

    @Test
    fun parsesPairedOriginalsWith64BitIdsAndRejectsTruncatedOrUnsafeMetadata() {
        val bytes = batch(content(42, 1000, jpeg, raw))
        val result = SonyContentParser.parse(bytes)
        assertEquals(listOf(1000L), result.timestamps)
        assertEquals(listOf("DSC00001.JPG", "DSC00001.ARW"), result.files.map { it.photo.filename })
        assertEquals(
            listOf(0x010000010000002aL, 0x010000020000002aL),
            result.files.map { it.photo.handle })
        assertEquals(3000L, result.files.first().modified)
        assertFailsWith<IllegalArgumentException> {
            SonyContentParser.parse(
                bytes.dropLast(1).toByteArray()
            )
        }
        assertFailsWith<IllegalArgumentException> { SonyContentParser.parse(bytes + byteArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> {
            SonyContentParser.parse(
                batch(
                    content(
                        1,
                        1000,
                        file(1, 0x3801, "a/..")
                    )
                )
            )
        }
    }

    @Test
    fun skipsVideoAndAudioMetadataOnMixedCardsWithoutLosingFollowingPhoto() {
        val video = file(5, 0xb982, "PRIVATE/CLIP.MP4").dropLast(8).toByteArray() +
                number(1, 4) + ByteArray(19 * 4) + number(1, 4) + ByteArray(4 * 4)
        val result = SonyContentParser.parse(batch(content(1, 1000, video), content(2, 2000, jpeg)))
        assertEquals(listOf(1000L, 2000L), result.timestamps)
        assertEquals("DSC00001.JPG", result.files.single().photo.filename)
    }

    private fun TestScope.catalog(
        dateIndex: Boolean,
        respond: (Int, List<Long>) -> ByteArray
    ): SonyContentCatalog {
        val replies = Channel<PtpIpPacket>(Channel.UNLIMITED)
        val transport = object : PtpIpCommandTransport {
            override suspend fun receive() = replies.receive()
            override suspend fun send(packet: PtpIpPacket) {
                fun read(at: Int, size: Int) = (0 until size).fold(0L) { value, i ->
                    value or ((packet.body[at + i].toLong() and 255) shl (8 * i))
                }
                val params = (10 until packet.body.size step 4).map { read(it, 4) }
                val data = respond(read(4, 2).toInt(), params)
                val id = read(6, 4).toInt()
                replies.send(PtpIpOperations.startData(id, data.size))
                replies.send(PtpIpOperations.endData(id, data))
                replies.send(PtpIpPacket(7, number(0x2001, 2) + number(id.toLong(), 4)))
            }
        }
        val operations = if (dateIndex) setOf(0x923b, 0x923c, 0x923d) else setOf(0x923c, 0x923d)
        val capabilities = SonyPtpInitializationResult.Ready("4.00", 310,
            SonyExtendedDeviceInfo(300, emptySet(), emptySet()), PtpDeviceInfo(operations), null)
        return SonyContentCatalog(PtpIpCommandQueue(transport, backgroundScope), capabilities)
    }

    @Test
    fun dateIndexLoadsNewestFirstAcrossDenseDaysAndRetriesTheSameOlderDay() = runTest {
        val day = 86_400_000L
        val newest = 2 * day
        val reads = mutableListOf<Long>()
        val records = (1..130).map { id ->
            val time = newest + id * 600_000
            time to content(id, time - 3_600_000, jpeg, raw, localTime = time)
        }
        val older = day + 10
        val all = listOf(older to content(0, older - 3_600_000, jpeg, localTime = older)) + records
        var rejectOlder = true
        val catalog = catalog(dateIndex = true) { code, params ->
            if (code == 0x923b) {
                check(params == listOf(1L))
                number(3, 4) + number(day, 8) + number(newest, 8) + number(day, 8)
            } else {
                check(code == 0x923c && params.last() == 1L)
                val from = params[0] or (params[1] shl 32)
                reads += from
                check(from != day || !rejectOlder)
                val matching = all.filter { it.first >= from }
                val returned = if (matching.isEmpty()) all.takeLast(100) else matching
                batch(*returned.take(params[2].toInt()).map { it.second }.toTypedArray())
            }
        }
        var page = catalog.open()
        assertTrue(page.hasMore)
        assertEquals(null, page.totalObjects)
        val loaded = mutableListOf<Int>()
        while (true) {
            val captures = groupCameraPhotos(page.photos)
            assertTrue(captures.all { it.files.size == 2 })
            loaded += captures.map { (it.preview.handle and 0xffffffffL).toInt() }
            assertTrue(reads.all { it >= newest }, "Older days must wait for scrolling")
            if (loaded.size == 130) break
            page = catalog.page(loaded.size)
        }
        assertEquals((130 downTo 1).toList(), loaded)
        assertFailsWith<IllegalStateException> { catalog.page(loaded.size) }
        rejectOlder = false
        val last = catalog.page(loaded.size)
        assertEquals(131, last.totalObjects)
        assertEquals(false, last.hasMore)
        assertEquals(0L, last.photos.single().handle and 0xffffffffL)
        assertEquals("DSC00001.JPG", catalog.revalidate(last.photos.single().handle).filename)
        assertEquals(older, reads.last(), "Revalidate using camera-local time")
    }

    @Test
    fun indexedCameraRepeatingItsFirstHundredRecordsStillShowsAndRevalidatesTheTail() = runTest {
        val day = 86_400_000L
        val records = (1..105).map { id -> content(id, day + id * 800_000L, jpeg) }
        val catalog = catalog(dateIndex = true) { code, params ->
            if (code == 0x923b) number(1, 4) + number(day, 8)
            else batch(*records.take(params[2].toInt()).toTypedArray())
        }
        var page = catalog.open()
        val handles = page.photos.map { it.handle }.toMutableList()
        while (page.hasMore) {
            page = catalog.page(handles.size)
            handles += page.photos.map { it.handle }
        }
        assertEquals((105L downTo 1L).toList(), handles.map { it and 0xffffffffL })
        assertEquals("DSC00001.JPG", catalog.revalidate(handles.first()).filename)
    }

    @Test
    fun repeatedFullUtcBatchRetainsPhotosAndRecoversTheTailWithAWiderRead() = runTest {
        val reads = mutableListOf<Pair<Long, Int>>()
        val records = (1..103).map { content(it, it.toLong(), jpeg) }
        val catalog = catalog(dateIndex = false) { _, params ->
            val limit = params[2].toInt()
            reads += params.first() to limit
            batch(*records.take(limit).toTypedArray())
        }
        val first = catalog.open()
        assertEquals(103, first.totalObjects)
        val files = first.photos + catalog.page(40).photos + catalog.page(80).photos
        assertEquals((103L downTo 1L).toList(), files.map { it.handle and 0xffffffffL })
        assertEquals(listOf(0L to 100, 100L to 100, 101L to 100, 101L to 200), reads)
    }

    @Test
    fun versionFourListsAndDownloadsPhoneSelectedPhotosWithoutOnCameraSelection() = runTest {
        val requests = mutableListOf<Pair<Int, List<Long>>>()
        val replies = Channel<PtpIpPacket>(Channel.UNLIMITED)
        var cardIdentity: Byte = 1
        var returnedTimestamp = 3000L
        var paginated = false
        val heifThumbnail = byteArrayOf(0, 0, 0, 16) + "ftypheic".encodeToByteArray() + ByteArray(4)
        var decodedThumbnail: ByteArray? = null
        var decodedPreview: ByteArray? = null
        var previewResponse = 0x2001L
        val transport = object : PtpIpCommandTransport {
            override suspend fun receive() = replies.receive()
            override suspend fun send(packet: PtpIpPacket) {
                if (packet.type != 6) return
                fun read(at: Int, size: Int): Long = (0 until size).fold(0L) { n, i ->
                    n or ((packet.body[at + i].toLong() and 255) shl (i * 8))
                }

                val code = read(4, 2).toInt()
                val id = read(6, 4).toInt()
                val params = (10 until packet.body.size step 4).map { read(it, 4) }
                requests += code to params
                val data = when (code) {
                    0x923c -> if (!paginated) batch(
                        content(42, 1000, jpeg, raw, heif),
                        identity = cardIdentity
                    )
                    else if (params.first() == 0L) batch(*(1..100).map {
                        content(
                            it,
                            999L + it,
                            jpeg,
                            raw
                        )
                    }.toTypedArray())
                    else batch(content(100, 1099, jpeg, raw), content(101, 1100, jpeg, raw))

                    0x923d -> ByteArray(params.last().toInt()) { 7 }
                    0x923e -> if (params.last() == 1L || previewResponse == 0x2001L) heifThumbnail else null
                    else -> error("Unexpected operation $code")
                }
                if (data != null) {
                    replies.send(PtpIpOperations.startData(id, data.size))
                    replies.send(PtpIpOperations.endData(id, data))
                }
                replies.send(
                    PtpIpPacket(
                        7,
                        number(
                            if (code == 0x923e && params.last() == 2L) previewResponse else 0x2001,
                            2
                        ) + number(id.toLong(), 4) +
                                if (code == 0x923d) number(returnedTimestamp, 8) else byteArrayOf()
                    )
                )
            }
        }
        val queue = PtpIpCommandQueue(transport, backgroundScope)
        val ready = SonyPtpInitializationResult.Ready(
            "4.00", 310,
            SonyExtendedDeviceInfo(300, emptySet(), emptySet()),
            PtpDeviceInfo(setOf(0x9207, 0x923c, 0x923d, 0x923e)), null
        )
        var committed = false
        var aborted = false
        var received = 0
        val transfer = SonyImageTransfer(queue, ready, decodeNonJpegThumbnail = { bytes ->
            decodedThumbnail = bytes
            null
        }, decodePreview = { bytes ->
            decodedPreview = bytes
            null
        }) {
            object : CameraImageDestination {
                override suspend fun write(bytes: ByteArray) {
                    received += bytes.size
                }

                override suspend fun commit() {
                    committed = true
                }

                override suspend fun abort() {
                    aborted = true
                }
            }
        }
        assertTrue(transfer.supported)
        val page = transfer.openBrowser()
        val handle = page.photos.first { it.mimeType == "image/x-sony-arw" }.handle
        val previewHandle = page.photos.first { it.mimeType == "image/jpeg" }.handle
        assertEquals(3, page.photos.size)
        assertEquals(listOf(0x923c to listOf(0L, 0L, 100L, 1L, 0L)), requests)
        val heifHandle = page.photos.first { it.mimeType == "image/heif" }.handle
        assertEquals(null, transfer.thumbnail(heifHandle))
        assertContentEquals(heifThumbnail, decodedThumbnail)
        assertEquals(0x923e to listOf(42L, 0x01000003L, 1L), requests.last())
        assertEquals(null, transfer.preview(previewHandle))
        assertContentEquals(heifThumbnail, decodedPreview)
        assertEquals(0x923e to listOf(42L, 0x01000001L, 2L), requests.last())
        assertTrue(requests.none { it.first == 0x923d }, "Preview must not fetch the original")
        val requestCount = requests.size
        assertEquals(null, transfer.preview(-1L))
        assertEquals(requestCount, requests.size)
        previewResponse = 0x2005
        decodedPreview = null
        assertEquals(null, transfer.preview(previewHandle))
        assertEquals(null, decodedPreview)
        assertEquals(null, transfer.thumbnail(heifHandle))
        assertEquals(
            1L,
            requests.last().second.last(),
            "Thumbnail remains available after rejected preview"
        )
        previewResponse = 0x2001
        assertEquals(WifiImageTransferStatus.Saved, transfer.download(handle) {}.status)
        assertTrue(committed)
        assertEquals(600_000, received)
        assertEquals(
            listOf(42L, 0x01000002L, 0L, 0L, 524288L),
            requests.first { it.first == 0x923d }.second
        )
        decodedPreview = null
        assertEquals(null, transfer.preview(handle))
        assertContentEquals(
            heifThumbnail,
            decodedPreview,
            "Unrecognized RAW layout uses the camera preview"
        )
        assertEquals(
            listOf(42L, 0x01000002L, 0L, 0L, 65536L),
            requests.last { it.first == 0x923d }.second
        )
        assertEquals(0x923e to listOf(42L, 0x01000002L, 2L), requests.last())
        returnedTimestamp = 3001
        assertEquals(WifiImageTransferStatus.Failed, transfer.download(handle) {}.status)
        assertTrue(aborted)
        cardIdentity = 2
        assertEquals(WifiImageTransferStatus.Failed, transfer.download(handle) {}.status)
        paginated = true
        val initialPage = transfer.openBrowser()
        assertEquals(101, initialPage.totalObjects)
        assertEquals(SonyImageTransfer.PAGE_SIZE * 2, initialPage.photos.size)
        assertTrue(initialPage.hasMore)
        assertEquals(101L, initialPage.photos.first().handle and 0xffffffffL)
        assertEquals(100L, initialPage.photos[2].handle and 0xffffffffL)
        assertEquals(0x923c to listOf(1099L, 0L, 100L, 1L, 0L), requests.last())
        val catalogRequests = requests.count { it.first == 0x923c }
        val nextPage = transfer.page(SonyImageTransfer.PAGE_SIZE)
        assertEquals(101L - SonyImageTransfer.PAGE_SIZE, nextPage.photos.first().handle and 0xffffffffL)
        val lastPage = transfer.page(100)
        assertEquals(101, lastPage.totalObjects)
        assertEquals(2, lastPage.photos.size)
        assertEquals(1L, lastPage.photos.first().handle and 0xffffffffL)
        assertEquals(false, lastPage.hasMore)
        assertEquals(catalogRequests, requests.count { it.first == 0x923c })
        val requestsBeforeClose = requests.size
        transfer.closeBrowser()
        assertEquals(requestsBeforeClose, requests.size)
        assertTrue(requests.none { it.first == 0x9207 })
        queue.close()
    }
}
