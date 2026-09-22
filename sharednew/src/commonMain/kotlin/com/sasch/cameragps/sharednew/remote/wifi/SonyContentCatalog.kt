package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal data class SonyContentFile(
    val photo: WifiCameraPhoto,
    val created: Long,
    val modified: Long
)

internal data class SonyContentBatch(
    val mediaIdentity: List<Byte>,
    val slot: Int,
    val timestamps: List<Long>,
    val files: List<SonyContentFile>,
)

/** Version-4 content records, including optional video/audio fields on mixed cards. */
internal object SonyContentParser {
    fun parse(data: ByteArray): SonyContentBatch {
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
            timestamps += created
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
                            size in 1..SonyImageTransfer.MAX_IMAGE_BYTES
                        ), created, modified
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
    private val capabilities: SonyPtpInitializationResult.Ready,
    private val events: PtpIpEventMonitor,
) {
    val hasThumbnails = capabilities.deviceInfo.supports(0x923e)
    private var requested = false
    private var enabled = false
    private var identity: List<Byte>? = null
    private val files = linkedMapOf<Long, SonyContentFile>()
    private var orderedFiles = emptyList<SonyContentFile>()

    suspend fun open(): CameraPhotoPage {
        if (!enabled) {
            requested = true
            control(true)
            enabled = true
        }
        identity = null
        files.clear()
        orderedFiles = emptyList()
        var cursor = 0L
        while (true) {
            val batch = fetch(cursor)
            verifyIdentity(batch)
            val previousCount = files.size
            batch.files.forEach { files[it.photo.handle] = it }
            require(files.size <= 100_000) { "Too many camera photos" }
            if (batch.timestamps.size < 100) break
            val last = batch.timestamps.last()
            require(last >= cursor) { "Camera catalog did not advance" }
            cursor =
                if (last == batch.timestamps.first() || files.size == previousCount) last + 1 else last
        }
        orderedFiles = files.values.sortedByDescending { it.created }
        return page(0)
    }

    fun page(offset: Int): CameraPhotoPage {
        require(offset >= 0 && offset <= orderedFiles.size)
        return CameraPhotoPage(
            orderedFiles.drop(offset).take(SonyImageTransfer.PAGE_SIZE).map { it.photo },
            offset, orderedFiles.size, offset + SonyImageTransfer.PAGE_SIZE < orderedFiles.size
        )
    }

    suspend fun revalidate(handle: Long): SonyImageInfo {
        val file = checkNotNull(files[handle])
        val batch = fetch(file.created)
        verifyIdentity(batch)
        val fresh = batch.files.singleOrNull { it.photo.handle == handle }
        check(fresh == file) { "Camera file changed; refresh the browser" }
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

    suspend fun close() {
        if (!requested) return
        control(false)
        requested = false
        enabled = false
        files.clear()
        orderedFiles = emptyList()
    }

    private suspend fun control(down: Boolean) = coroutineScope {
        val result =
            async(start = CoroutineStart.UNDISPATCHED) { events.awaitControlResult(0xd30f, 3_000) }
        try {
            val flags =
                if ((capabilities.vendorCodeVersion ?: 0) >= 310) listOf(1L) else emptyList()
            val response = commands.executeDataOut(
                0x9207,
                listOf(0xd30fL) + flags,
                byteArrayOf(if (down) 2 else 1, 0)
            )
            check(response is PtpIpTransactionResult.Response && response.response.code == 0x2001)
            check(result.await() == 1L) { "Camera did not confirm transfer mode" }
        } finally {
            result.cancel()
        }
    }

    private fun verifyIdentity(batch: SonyContentBatch) {
        check(batch.slot == 1) { "Unexpected camera card" }
        check(identity == null || identity == batch.mediaIdentity) { "Camera card changed; refresh the browser" }
        identity = batch.mediaIdentity
    }

    private suspend fun fetch(time: Long) = SonyContentParser.parse(
        data(
            0x923c,
            listOf(time and 0xffffffffL, time ushr 32, 100L, 1L, 0L), 2 * 1024 * 1024
        )
    )

    private suspend fun data(code: Int, parameters: List<Long>, limit: Int): ByteArray {
        val result = commands.executeDataIn(code, parameters, maxDataBytes = limit)
        check(result is PtpIpTransactionResult.Response && result.response.code == 0x2001)
        return result.data ?: error("Missing camera catalog data")
    }

    companion object {
        fun supported(capabilities: SonyPtpInitializationResult.Ready) =
            listOf(0x9207, 0x923c, 0x923d).all(capabilities.deviceInfo::supports) &&
                    capabilities.extendedInfo.supportsControl(0xd30f)
    }
}
