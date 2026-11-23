package com.sasch.cameragps.sharednew.remote.wifi

import com.diamondedge.logging.logging
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal data class SonyContentFile(
    val photo: WifiCameraPhoto,
    val created: Long,
    val modified: Long,
    val localCreated: Long,
)

internal data class SonyContentBatch(
    val mediaIdentity: List<Byte>,
    val slot: Int,
    val timestamps: List<Long>,
    val files: List<SonyContentFile>,
)

/** Version-4 content records, including optional video/audio fields on mixed cards. */
internal object SonyContentParser {
    fun parse(data: ByteArray, localTime: Boolean = false): SonyContentBatch {
        var offset = 0
        fun take(size: Int): Int {
            require(size >= 0 && size <= data.size - offset) { "Truncated camera content list" }
            return offset.also { offset += size }
        }

        fun number(size: Int): Long {
            val at = take(size)
            return (0 until size).fold(0L) { n, i -> n or ((data[at + i].toLong() and 255) shl (8 * i)) }
        }

        fun count(limit: Int): Int = number(4).also { require(it in 0..limit.toLong()) }.toInt()
        number(2) // Dataset version.
        take(2)
        number(8) // Database update time.
        val identityOffset = take(128)
        val identity = data.copyOfRange(identityOffset, identityOffset + 128).toList()
        val slot = number(4).toInt()
        require(slot in 1..2)
        val timestamps = mutableListOf<Long>()
        val files = mutableListOf<SonyContentFile>()
        repeat(count(1000)) {
            number(4) // Content type.
            val contentId = number(4)
            take(20) // Directory, file, group type/id, representative flag.
            val created = number(8)
            val modified = number(8)
            val localCreated = number(8)
            number(8)
            require(created >= 0 && modified >= 0 && localCreated >= 0)
            timestamps += if (localTime) localCreated else created
            take(8) // Rating, protection flag.
            val dummy = number(4) != 0L
            take(count(4096)) // Shot marks.
            repeat(count(64)) {
                val fileId = number(2)
                take(2)
                val pathLength = count(4096)
                val pathStart = take(pathLength)
                val path = data.copyOfRange(pathStart, pathStart + pathLength)
                    .decodeToString(throwOnInvalidSequence = true).trimEnd('\u0000')
                val format = number(4).toInt()
                val size = number(8)
                require(size >= 0)
                take(32) // UMID.
                if (number(4) != 0L) take(8)
                if (number(4) != 0L) take(19 * 4)
                if (number(4) != 0L) take(4 * 4)
                val mime = when (format) {
                    0x3801 -> "image/jpeg"
                    0xb101 -> "image/x-sony-arw"
                    0xb110 -> "image/heif"
                    else -> ""
                }
                if (!dummy && mime.isNotEmpty() && fileId in 1..3) {
                    val name = path.substringAfterLast('/').substringAfterLast('\\')
                    require(
                        name.length in 1..200 && name != "." && name != ".." &&
                                name.none { it.code < 32 || it.code == 127 }) { "Invalid image filename" }
                    val id = (slot.toLong() shl 56) or (fileId shl 32) or contentId
                    val date =
                        Instant.fromEpochMilliseconds(localCreated).toLocalDateTime(TimeZone.UTC)
                            .toString()
                    files += SonyContentFile(
                        WifiCameraPhoto(
                            id, name, size, mime, date,
                            size in 1..SonyImageTransfer.MAX_IMAGE_BYTES,
                            captureId = "content:$slot:$contentId"
                        ), created, modified, localCreated
                    )
                }
            }
        }
        require(offset == data.size) { "Trailing camera content bytes" }
        require(files.map { it.photo.handle }
            .distinct().size == files.size) { "Duplicate camera file" }
        return SonyContentBatch(identity, slot, timestamps, files)
    }
}

/** Sony's version-4 card catalog uses 64-bit content IDs, not PTP object handles. */
internal class SonyContentCatalog(
    private val commands: PtpIpCommandQueue,
    capabilities: SonyPtpInitializationResult.Ready,
) {
    val hasThumbnails = capabilities.deviceInfo.supports(0x923e)
    private val log = logging("SonyContentCatalog")
    private var identity: List<Byte>? = null
    private val files = linkedMapOf<Long, SonyContentFile>()
    private val captures = mutableListOf<WifiCameraCapture>()
    private val hasDateIndex = capabilities.deviceInfo.supports(0x923b)
    private val remainingPeriods = ArrayDeque<LongRange>()

    suspend fun open(): CameraPhotoPage {
        clear()
        log.i { "Wi-Fi photo browser reading the slot 1 catalog" }
        if (hasDateIndex) {
            val data = data(0x923b, listOf(1L), 4 + 8 * 100_000)
            parseCaptureDays(data).forEach { day -> remainingPeriods.addLast(day until day + DAY_MS) }
        } else {
            readUtcCatalog()
        }
        return page(0)
    }

    suspend fun page(offset: Int): CameraPhotoPage {
        require(offset in 0..captures.size)
        while (offset == captures.size && remainingPeriods.isNotEmpty()) readNextPeriod()
        val page = cameraPhotoPage(captures, offset)
        return if (remainingPeriods.isEmpty()) page else page.copy(totalObjects = null, hasMore = true)
    }

    private suspend fun readNextPeriod() {
        val period = remainingPeriods.first()
        var limit = BATCH_SIZE
        while (true) {
            val batch = fetch(period.first, limit, localTime = true)
            verifyIdentity(batch)
            val last = batch.timestamps.maxOrNull()
            val repeatedBoundary = last != null && (last < period.first ||
                (last in period && batch.timestamps.first() == last))
            if (batch.timestamps.size == limit && repeatedBoundary && limit < MAX_BATCH_SIZE) {
                limit = minOf(limit * 2, MAX_BATCH_SIZE)
                continue
            }
            if (batch.timestamps.size < limit || last == null || last !in period) {
                addCaptures(batch.files.filter { it.localCreated in period })
                remainingPeriods.removeFirst()
                return
            }
            if (period.first == period.last) {
                // A burst can share one millisecond. Fetch the whole group before publishing it.
                check(limit < MAX_BATCH_SIZE) { "Camera catalog timestamp group exceeds the record limit" }
                limit = minOf(limit * 2, MAX_BATCH_SIZE)
                continue
            }
            // Sony reads forward from an inclusive timestamp. Split a full interval so we can
            // return its newest captures before reading the rest of a large shooting day.
            val middle = period.first + (period.last - period.first) / 2 + 1
            val split = maxOf(middle, last)
            remainingPeriods.removeFirst()
            remainingPeriods.addFirst(period.first until split)
            remainingPeriods.addFirst(split..period.last)
            return
        }
    }

    private suspend fun readUtcCatalog() {
        val found = linkedMapOf<Long, SonyContentFile>()
        var cursor = 0L
        var limit = BATCH_SIZE
        while (true) {
            val batch = fetch(cursor, limit)
            verifyIdentity(batch)
            batch.files.associateByTo(found) { it.photo.handle }
            require(found.size <= 100_000) { "Too many camera photos" }
            if (batch.timestamps.size < limit) break
            val last = batch.timestamps.max()
            if (last < cursor || (last == cursor && batch.timestamps.all { it == cursor })) {
                if (limit == MAX_BATCH_SIZE) {
                    log.w { "Camera repeated a full catalog batch at the record limit" }
                    break
                }
                limit = minOf(limit * 2, MAX_BATCH_SIZE)
            } else {
                if (last == Long.MAX_VALUE) break
                cursor = if (last == cursor) last + 1 else last
                limit = BATCH_SIZE
            }
        }
        addCaptures(found.values)
    }

    private fun addCaptures(batch: Collection<SonyContentFile>) {
        val newFiles = batch.filter { it.photo.handle !in files }
        require(files.size + newFiles.size <= 100_000) { "Too many camera photos" }
        newFiles.associateByTo(files) { it.photo.handle }
        captures += groupCameraPhotos(newFiles.sortedByDescending { it.created }.map { it.photo })
    }

    suspend fun revalidate(handle: Long): SonyImageInfo {
        val file = checkNotNull(files[handle])
        val cursor = if (hasDateIndex) file.localCreated else file.created
        var limit = BATCH_SIZE
        while (true) {
            val batch = fetch(cursor, limit, localTime = hasDateIndex)
            verifyIdentity(batch)
            val fresh = batch.files.singleOrNull { it.photo.handle == handle }
            if (fresh != null || batch.timestamps.size < limit || batch.timestamps.any { it > cursor } ||
                limit == MAX_BATCH_SIZE
            ) {
                check(fresh == file) { "Camera file changed; refresh the browser" }
                break
            }
            limit = minOf(limit * 2, MAX_BATCH_SIZE)
        }
        return SonyImageInfo(
            file.photo.size,
            file.photo.filename,
            file.photo.mimeType,
            file.photo.capturedAt
        )
    }

    suspend fun read(handle: Long, offset: Long, length: Int): ByteArray {
        val response = commands.executeDataIn(
            0x923d,
            listOf(
                handle and 0xffffffffL,
                handle ushr 32,
                offset and 0xffffffffL,
                offset ushr 32,
                length.toLong()
            ),
            maxDataBytes = length
        )
        check(response is PtpIpTransactionResult.Response && response.response.code == 0x2001)
        if (response.response.parameters.size >= 2) {
            val timestamp =
                response.response.parameters[0] or (response.response.parameters[1] shl 32)
            check(timestamp <= checkNotNull(files[handle]).modified) { "Camera file modified during transfer" }
        }
        return response.data ?: error("Missing image data")
    }

    suspend fun thumbnail(handle: Long): ByteArray = data(
        0x923e,
        listOf(handle and 0xffffffffL, handle ushr 32, 1L), 512 * 1024
    )

    suspend fun preview(handle: Long): ByteArray = data(
        0x923e,
        listOf(handle and 0xffffffffL, handle ushr 32, 2L), 8 * 1024 * 1024
    )

    fun clear() {
        identity = null
        files.clear()
        captures.clear()
        remainingPeriods.clear()
    }

    private fun verifyIdentity(batch: SonyContentBatch) {
        check(batch.slot == 1) { "Unexpected camera card" }
        check(identity == null || identity == batch.mediaIdentity) { "Camera card changed; refresh the browser" }
        identity = batch.mediaIdentity
    }

    private suspend fun fetch(time: Long, limit: Int = BATCH_SIZE, localTime: Boolean = false): SonyContentBatch = try {
        val bytes = data(
            0x923c, listOf(time and 0xffffffffL, time ushr 32, limit.toLong(), 1L, if (localTime) 1L else 0L), 2 * 1024 * 1024
        )
        SonyContentParser.parse(bytes, localTime)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        log.w { "Wi-Fi slot 1 photo catalog read failed: ${failure.message}" }
        throw failure
    }

    private suspend fun data(code: Int, parameters: List<Long>, limit: Int): ByteArray {
        val result = commands.executeDataIn(code, parameters, maxDataBytes = limit)
        check(result is PtpIpTransactionResult.Response && result.response.code == 0x2001) {
            "Wi-Fi photo operation 0x${code.toString(16)}: ${result.diagnosticSummary()}"
        }
        return result.data ?: error("Missing camera catalog data")
    }

    companion object {
        private const val BATCH_SIZE = 100
        private const val MAX_BATCH_SIZE = 1000
        private const val DAY_MS = 86_400_000L

        private fun parseCaptureDays(data: ByteArray): List<Long> {
            fun number(at: Int, size: Int) = (0 until size).fold(0L) { value, i ->
                value or ((data[at + i].toLong() and 255) shl (8 * i))
            }
            require(data.size >= 4)
            val count = number(0, 4)
            require(data.size.toLong() == 4 + count * 8) { "Invalid camera capture dates" }
            return List(count.toInt()) { index ->
                val time = number(4 + index * 8, 8)
                require(time in 0..Long.MAX_VALUE - DAY_MS)
                time - time % DAY_MS
            }.distinct().sortedDescending()
        }

        fun supported(capabilities: SonyPtpInitializationResult.Ready) =
            capabilities.isRemoteWithTransfer && capabilities.deviceInfo.supports(0x923c) &&
                    capabilities.deviceInfo.supports(0x923d)
    }
}
