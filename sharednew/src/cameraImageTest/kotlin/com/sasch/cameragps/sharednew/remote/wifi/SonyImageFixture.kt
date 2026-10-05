package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal data class SonyImageFixture(
    val file: String,
    val format: String,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val metadata: Map<String, String>,
    val bitDepth: Int,
    val chroma: Int,
) {
    val displayWidth get() = if (orientation in 5..8) height else width
    val displayHeight get() = if (orientation in 5..8) width else height

    fun verifyMetadata(actual: Map<String, String>) {
        metadata.forEach { (tag, expected) -> assertEquals(expected, actual[tag], "$file: $tag") }
    }

    fun verifyHeifEncoding(bytes: ByteArray) {
        fun byte(at: Int) = bytes[at].toInt() and 255
        fun uint32(at: Int) =
            (0..3).fold(0L) { value, index -> (value shl 8) or byte(at + index).toLong() }

        val configurations = mutableListOf<Pair<Int, Int>>()
        fun walk(start: Int, end: Int) {
            var offset = start
            while (offset + 8 <= end) {
                val size = uint32(offset)
                val type = bytes.decodeToString(offset + 4, offset + 8)
                val length = if (size == 0L) (end - offset).toLong() else size
                require(length in 8..(end - offset).toLong()) { "$file: invalid HEIF box" }
                val next = offset + length.toInt()
                when (type) {
                    "meta" -> walk(offset + 12, next)
                    "iprp", "ipco" -> walk(offset + 8, next)
                    "hvcC" -> {
                        require(length >= 31) { "$file: truncated HEVC configuration" }
                        val depth = 8 + (byte(offset + 8 + 17) and 7)
                        val sampling = when (byte(offset + 8 + 16) and 3) {
                            1 -> 420; 2 -> 422; 3 -> 444; else -> 400
                        }
                        configurations += depth to sampling
                    }
                }
                offset = next
            }
        }
        walk(0, bytes.size)
        // Camera HIFs can also contain 4:2:0 thumbnails alongside their 4:2:2 tiles.
        assertTrue(
            bitDepth to chroma in configurations,
            "$file: expected $bitDepth-bit $chroma HEVC, found $configurations"
        )
    }

    fun verifyImage(image: ImageBitmap, maxDimension: Int? = null) {
        val scale =
            maxDimension?.let { minOf(1.0, it.toDouble() / maxOf(displayWidth, displayHeight)) }
                ?: 1.0
        if (scale == 1.0) {
            assertEquals(displayWidth, image.width, "$file: full-resolution width")
            assertEquals(displayHeight, image.height, "$file: full-resolution height")
        } else {
            // ImageIO may round a scaled dimension down to an even pixel count.
            assertTrue(
                abs(image.width - (displayWidth * scale).toInt()) <= 1,
                "$file: unexpected width ${image.width}"
            )
            assertTrue(
                abs(image.height - (displayHeight * scale).toInt()) <= 1,
                "$file: unexpected height ${image.height}"
            )
        }
        val pixels = IntArray(16)
        for (y in 0..3) for (x in 0..3) {
            val pixel = IntArray(1)
            image.readPixels(
                pixel,
                startX = image.width * (x + 1) / 5,
                startY = image.height * (y + 1) / 5,
                width = 1,
                height = 1
            )
            pixels[y * 4 + x] = pixel[0]
        }
        assertTrue(pixels.all { it ushr 24 == 255 }, "$file: unexpected transparent pixels")
        val levels = pixels.map { ((it ushr 16 and 255) + (it ushr 8 and 255) + (it and 255)) / 3 }
        assertTrue(levels.max() - levels.min() > 16, "$file: image is blank or nearly uniform")
    }
}
