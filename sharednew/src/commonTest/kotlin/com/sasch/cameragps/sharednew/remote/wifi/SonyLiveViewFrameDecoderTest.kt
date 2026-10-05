package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SonyLiveViewFrameDecoderTest {
    private fun frame(image: ByteArray, focal: ByteArray = ByteArray(0)): ByteArray {
        val imageOffset = 16 + focal.size
        val header = ByteArray(16)
        val values = listOf(imageOffset, image.size, if (focal.isEmpty()) 0 else 16, focal.size)
        values.forEachIndexed { index, value ->
            (0..3).forEach { byte -> header[index * 4 + byte] = (value ushr (byte * 8)).toByte() }
        }
        return header + focal + image
    }

    @Test
    fun handlesSplitHeadersAndConsecutiveFrames() {
        val decoder = SonyLiveViewFrameDecoder(128)
        val first = frame(testJpeg(), byteArrayOf(7, 8))
        val second = frame(testJpeg() + ByteArray(11))
        assertEquals(0, decoder.feed(first.copyOfRange(0, 7)).size)
        val decoded = decoder.feed(first.copyOfRange(7, first.size) + second)
        assertEquals(2, decoded.size)
        assertContentEquals(testJpeg(), decoded[0].imageBytes)
        assertContentEquals(byteArrayOf(7, 8), decoded[0].focalMetadata)
        assertContentEquals(testJpeg(), decoded[1].imageBytes)
        assertEquals(640, decoded[1].width)
        assertEquals(424, decoded[1].height)
    }

    @Test
    fun acceptsObservedA6700LayoutWithReservedBytesAndTrailingPadding() {
        val header = ByteArray(16)
        listOf(160, 18176, 24, 136).forEachIndexed { index, value ->
            (0..3).forEach { byte -> header[index * 4 + byte] = (value ushr (byte * 8)).toByte() }
        }
        val jpeg = testJpeg()
        val payload = header + ByteArray(8) + ByteArray(136) + jpeg + ByteArray(18176 - jpeg.size)
        val result = SonyLiveViewFrameDecoder().feed(payload).single()
        assertContentEquals(jpeg, result.imageBytes)
        assertEquals(136, result.focalMetadata.size)
    }

    @Test
    fun rejectsOversizedOrWrappedExtentsBeforeAllocation() {
        val decoder = SonyLiveViewFrameDecoder(128)
        val tooLarge = frame(byteArrayOf(1)).copyOf().also { it[4] = 127 }
        assertFailsWith<IllegalArgumentException> { decoder.feed(tooLarge) }
        decoder.reset()
        val wrapped = frame(byteArrayOf(1)).copyOf().also {
            it[0] = 0xff.toByte()
            it[1] = 0xff.toByte()
            it[2] = 0xff.toByte()
            it[3] = 0xff.toByte()
        }
        assertFailsWith<IllegalArgumentException> { decoder.feed(wrapped) }
    }
}

internal fun testJpeg(): ByteArray = listOf(
    0xff, 0xd8, 0xff, 0xc0, 0, 11, 8, 1, 0xa8, 2, 0x80, 1, 1, 0x11, 0, 0xff, 0xd9,
).map(Int::toByte).toByteArray()
