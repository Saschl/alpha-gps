package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal interface CameraImageDestination {
    suspend fun write(bytes: ByteArray)
    suspend fun commit()

    /** Removes an incomplete file; harmless after commit. */
    suspend fun abort()
}

internal fun interface CameraImageStore {
    suspend fun create(info: SonyImageInfo): CameraImageDestination
}

internal data class SonyImageInfo(
    val size: Long,
    val filename: String,
    val mimeType: String,
    val capturedAt: String = ""
) {
    companion object {
        fun parse(data: ByteArray): SonyImageInfo {
            require(data.size >= 53) { "Truncated image information" }
            fun number(offset: Int, size: Int): Long = (0 until size).fold(0L) { value, i ->
                value or ((data[offset + i].toLong() and 255) shl (i * 8))
            }

            val format = number(4, 2).toInt()
            val size = number(8, 4)
            val mime = when (format) {
                0x3801 -> "image/jpeg"
                0xb101 -> "image/x-sony-arw"
                0xb110 -> "image/heif"
                else -> ""
            }
            var offset = 52
            fun string(): String {
                require(offset < data.size)
                val count = data[offset++].toInt() and 255
                require(count * 2 <= data.size - offset)
                if (count == 0) return ""
                val chars = CharArray(count) { number(offset + it * 2, 2).toInt().toChar() }
                offset += count * 2
                require(chars.last() == '\u0000') { "Unterminated image filename" }
                return chars.concatToString(0, count - 1)
            }

            val filename = string()
            val capturedAt = string()
            repeat(2) { string() }
            require(offset == data.size)
            require(
                filename.length in 1..200 && filename != "." && filename != ".." &&
                        filename.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) {
                "Invalid image filename"
            }
            return SonyImageInfo(size, filename, mime, capturedAt)
        }
    }
}

internal data class CameraPhotoPage(
    val photos: List<WifiCameraPhoto>,
    val offset: Int,
    val totalObjects: Int,
    val hasMore: Boolean = false
)

/** Card browsing is exclusive with live view and shutter operation. */
internal class SonyImageTransfer(
    private val commands: PtpIpCommandQueue,
    private val capabilities: SonyPtpInitializationResult.Ready,
    events: PtpIpEventMonitor? = null,
    private val decodeNonJpegThumbnail: (ByteArray) -> ImageBitmap? = { null },
    private val store: CameraImageStore,
) {
    private val catalog = if (events != null && SonyContentCatalog.supported(capabilities))
        SonyContentCatalog(commands, capabilities, events) else null
    val supported: Boolean = catalog != null || listOf(
        GET_STORAGE_IDS,
        GET_HANDLES,
        GET_OBJECT_INFO,
        GET_PROPERTIES,
        SET_TRANSFER_MODE
    )
        .all(capabilities.deviceInfo::supports) &&
            (capabilities.deviceInfo.supports(GET_PARTIAL_LARGE_OBJECT) || capabilities.deviceInfo.supports(
                GET_PARTIAL_OBJECT
            )) &&
            capabilities.extendedInfo.supportsProperty(TRANSFER_ENABLED)
    private var modeRequested = false
    private var handles = emptyList<Long>()
    private var pagePhotos = emptyList<WifiCameraPhoto>()

    suspend fun openBrowser(): CameraPhotoPage {
        check(supported)
        catalog?.let { return it.open().also { page -> pagePhotos = page.photos } }
        if (!modeRequested) {
            modeRequested = true
            accepted(commands.executeNoData(SET_TRANSFER_MODE, listOf(2L, 1L, 0L)))
        }
        withTimeout(10.seconds) {
            val flags =
                if ((capabilities.vendorCodeVersion ?: 0) >= 310) listOf(1L) else emptyList()
            while (true) {
                val properties = SonyCameraProperties.parse(
                    data(GET_PROPERTIES, listOf(0L) + flags),
                    capabilities.extendedInfo.controlCodes, setOf(TRANSFER_ENABLED)
                )
                if (properties[TRANSFER_ENABLED]?.value?.and(0xffff) == 1L) break
                delay(250.milliseconds)
            }
        }
        val storages = parseIds(data(GET_STORAGE_IDS, limit = 1028), 256)
        val found = mutableListOf<Long>()
        for (storage in storages) {
            found += parseIds(data(GET_HANDLES, listOf(storage, 0L, 0L), 400_004), 100_000)
            require(found.size <= 100_000) { "Too many camera objects" }
        }
        handles = found.distinct().asReversed()
        return page(0)
    }

    suspend fun page(offset: Int): CameraPhotoPage {
        catalog?.let { return it.page(offset).also { page -> pagePhotos = page.photos } }
        check(modeRequested)
        require(offset >= 0 && (offset < handles.size || offset == 0))
        val photos = mutableListOf<WifiCameraPhoto>()
        for (handle in handles.drop(offset).take(PAGE_SIZE)) {
            val info = SonyImageInfo.parse(data(GET_OBJECT_INFO, listOf(handle), 4096))
            if (info.mimeType.isNotEmpty()) photos += WifiCameraPhoto(
                handle, info.filename, info.size,
                info.mimeType, info.capturedAt, info.size in 1..MAX_IMAGE_BYTES
            )
        }
        pagePhotos = photos
        return CameraPhotoPage(photos, offset, handles.size)
    }

    suspend fun thumbnail(handle: Long): ImageBitmap? {
        if (!(catalog?.hasThumbnails
                ?: capabilities.deviceInfo.supports(GET_THUMB)) || pagePhotos.none { it.handle == handle }
        ) return null
        return try {
            val bytes = catalog?.thumbnail(handle) ?: data(GET_THUMB, listOf(handle), 512 * 1024)
            withContext(Dispatchers.Default) {
                if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) {
                    val jpeg = SonyJpeg.inspect(bytes)
                    require(jpeg.width <= 640 && jpeg.height <= 640)
                    bytes.copyOf(jpeg.byteCount).decodeToImageBitmap()
                } else decodeNonJpegThumbnail(bytes)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun download(
        handle: Long,
        onProgress: (WifiImageTransferState) -> Unit
    ): WifiImageTransferState {
        val photo = pagePhotos.singleOrNull { it.handle == handle && it.downloadable }
            ?: return WifiImageTransferState(WifiImageTransferStatus.Failed)
        var destination: CameraImageDestination? = null
        var committed = false
        try {
            return withTimeout(5.minutes) {
                // Re-read metadata before saving: the card may have changed since listing.
                val info = catalog?.revalidate(handle) ?: SonyImageInfo.parse(
                    data(
                        GET_OBJECT_INFO,
                        listOf(handle),
                        4096
                    )
                )
                check(
                    info.filename == photo.filename && info.size == photo.size && info.mimeType == photo.mimeType &&
                            info.capturedAt == photo.capturedAt
                )
                val output = store.create(info)
                destination = output
                var offset = 0L
                while (offset < info.size) {
                    val length = minOf(CHUNK_BYTES.toLong(), info.size - offset).toInt()
                    val bytes = if (catalog != null) catalog.read(handle, offset, length) else {
                        val large = capabilities.deviceInfo.supports(GET_PARTIAL_LARGE_OBJECT)
                        val parameters = if (large) listOf(
                            handle,
                            offset and 0xffffffffL,
                            offset ushr 32,
                            length.toLong()
                        )
                        else listOf(handle, offset, length.toLong())
                        val result = commands.executeDataIn(
                            if (large) GET_PARTIAL_LARGE_OBJECT else GET_PARTIAL_OBJECT,
                            parameters, maxDataBytes = length
                        )
                        accepted(result)
                        result as PtpIpTransactionResult.Response
                        if (!large) require(result.response.parameters.firstOrNull() == length.toLong())
                        result.data ?: error("Missing image data")
                    }
                    require(bytes.size == length) { "Incomplete image chunk" }
                    output.write(bytes)
                    offset += length
                    onProgress(
                        WifiImageTransferState(
                            WifiImageTransferStatus.Downloading,
                            photo.filename,
                            offset,
                            info.size
                        )
                    )
                }
                withContext(NonCancellable) {
                    output.commit()
                    committed = true
                    destination = null
                }
                WifiImageTransferState(WifiImageTransferStatus.Saved, photo.filename)
            }
        } catch (_: TimeoutCancellationException) {
            return WifiImageTransferState(
                if (committed) WifiImageTransferStatus.Saved else WifiImageTransferStatus.Failed,
                photo.filename
            )
        } catch (cancelled: CancellationException) {
            if (committed) return WifiImageTransferState(
                WifiImageTransferStatus.Saved,
                photo.filename
            )
            throw cancelled
        } catch (_: Exception) {
            return WifiImageTransferState(WifiImageTransferStatus.Failed, photo.filename)
        } finally {
            withContext(NonCancellable) { destination?.abort() }
        }
    }

    suspend fun closeBrowser() {
        catalog?.let { it.close(); pagePhotos = emptyList(); return }
        if (!modeRequested) return
        // A failed exit must not restart shutter/live view while the camera is still in transfer mode.
        accepted(commands.executeNoData(SET_TRANSFER_MODE, listOf(2L, 0L, 0L)))
        modeRequested = false
        handles = emptyList()
        pagePhotos = emptyList()
    }

    private suspend fun data(
        code: Int,
        parameters: List<Long> = emptyList(),
        limit: Int = 8 * 1024 * 1024
    ): ByteArray {
        val result = commands.executeDataIn(code, parameters, maxDataBytes = limit)
        accepted(result)
        return (result as PtpIpTransactionResult.Response).data ?: error("Missing camera data")
    }

    private fun accepted(result: PtpIpTransactionResult) {
        check(result is PtpIpTransactionResult.Response && result.response.code == 0x2001) { "Camera refused image operation" }
    }

    internal companion object {
        const val PAGE_SIZE = 20
        const val MAX_IMAGE_BYTES = 512L * 1024 * 1024
        private const val GET_STORAGE_IDS = 0x1004
        private const val GET_HANDLES = 0x1007
        private const val GET_OBJECT_INFO = 0x1008
        private const val GET_THUMB = 0x100a
        private const val GET_PARTIAL_OBJECT = 0x101b
        private const val GET_PARTIAL_LARGE_OBJECT = 0x9211
        private const val SET_TRANSFER_MODE = 0x9212
        private const val GET_PROPERTIES = 0x9209
        private const val TRANSFER_ENABLED = 0xd295
        private const val CHUNK_BYTES = 512 * 1024

        fun parseIds(data: ByteArray, limit: Int): List<Long> {
            require(data.size >= 4)
            fun number(offset: Int) =
                (0..3).fold(0L) { n, i -> n or ((data[offset + i].toLong() and 255) shl (8 * i)) }

            val count = number(0)
            require(count <= limit && data.size.toLong() == 4L + count * 4)
            return List(count.toInt()) { number(4 + it * 4).also { id -> require(id in 1..0xfffffffeL) } }
        }
    }
}
