package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import org.jetbrains.compose.resources.decodeToImageBitmap

internal data class ArwJpegPreview(val bytes: ByteArray, val orientation: Int) {
    fun decode(): ImageBitmap {
        val source = bytes.decodeToImageBitmap()
        if (orientation == 1) return source
        val swap = orientation in 5..8
        val output = ImageBitmap(
            if (swap) source.height else source.width,
            if (swap) source.width else source.height
        )
        val canvas = Canvas(output)
        canvas.translate(output.width / 2f, output.height / 2f)
        when (orientation) {
            2 -> canvas.scale(-1f, 1f)
            3 -> canvas.rotate(180f)
            4 -> canvas.scale(1f, -1f)
            5 -> {
                canvas.rotate(90f); canvas.scale(1f, -1f)
            }

            6 -> canvas.rotate(90f)
            7 -> {
                canvas.rotate(90f); canvas.scale(-1f, 1f)
            }

            8 -> canvas.rotate(-90f)
        }
        canvas.drawImage(source, Offset(-source.width / 2f, -source.height / 2f), Paint())
        return output
    }
}

/** Sony's primary TIFF IFD points to its medium JPEG, separate from the RAW sensor data. */
internal object SonyArwPreview {
    suspend fun read(size: Long, readRange: suspend (Long, Int) -> ByteArray): ArwJpegPreview? {
        if (size < 8) return null
        val headerSize = minOf(size, 64 * 1024L).toInt()
        val header = readRange(0, headerSize)
        require(header.size == headerSize) { "Incomplete ARW header" }
        val littleEndian = when (header.copyOfRange(0, 2).decodeToString()) {
            "II" -> true
            "MM" -> false
            else -> return null
        }

        fun number(offset: Int, length: Int): Long {
            require(offset >= 0 && length <= header.size - offset) { "Invalid ARW metadata offset" }
            return (0 until length).fold(0L) { value, index ->
                val shift = 8 * if (littleEndian) index else length - index - 1
                value or ((header[offset + index].toLong() and 255) shl shift)
            }
        }
        if (number(2, 2) != 42L) return null
        val offset = number(4, 4)
        require(offset in 8L..(header.size - 2).toLong()) { "ARW preview metadata outside header" }
        val count = number(offset.toInt(), 2).toInt()
        require(count <= 512 && offset + 2 + count * 12L + 4 <= header.size) { "Invalid ARW IFD" }
        val tags = mutableMapOf<Int, Long>()
        repeat(count) { index ->
            val entry = offset.toInt() + 2 + index * 12
            val tag = number(entry, 2).toInt()
            if (tag in setOf(0x0103, 0x0112, 0x0201, 0x0202)) {
                val type = number(entry + 2, 2).toInt()
                require(type in 3..4 && number(entry + 4, 4) == 1L) { "Invalid ARW preview tag" }
                require(tag !in tags) { "Duplicate ARW preview tag" }
                tags[tag] = number(entry + 8, if (type == 3) 2 else 4)
            }
        }
        if (tags[0x0103] != 6L) return null
        val jpegOffset = tags[0x0201] ?: return null
        val jpegSize = tags[0x0202] ?: return null
        val orientation = tags[0x0112]?.toInt() ?: 1
        require(orientation in 1..8) { "Invalid ARW orientation" }
        require(jpegOffset >= 8 && jpegSize in 4..2 * 1024 * 1024L && jpegOffset <= size - jpegSize) {
            "Invalid ARW preview range"
        }
        val bytes = if (jpegOffset + jpegSize <= header.size) {
            header.copyOfRange(jpegOffset.toInt(), (jpegOffset + jpegSize).toInt())
        } else readRange(jpegOffset, jpegSize.toInt())
        require(bytes.size == jpegSize.toInt()) { "Incomplete ARW JPEG preview" }
        val jpeg = SonyJpeg.inspect(bytes)
        if (maxOf(jpeg.width, jpeg.height) !in 641..2048) return null
        return ArwJpegPreview(bytes.copyOf(jpeg.byteCount), orientation)
    }
}
