package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SonyArwPreviewTest {
    private fun mediumJpeg() = testJpeg().also {
        it[7] = 4; it[8] = 0x38 // 1080
        it[9] = 6; it[10] = 0x50 // 1616
    }

    private fun header(
        bigEndian: Boolean = false, jpegOffset: Long = 200_000, jpegSize: Int = mediumJpeg().size,
        orientation: Int = 1
    ): ByteArray = ByteArray(65536).also { bytes ->
        fun put(at: Int, value: Long, length: Int) {
            repeat(length) {
                bytes[at + it] = (value ushr (8 * if (bigEndian) length - it - 1 else it)).toByte()
            }
        }
        bytes[0] = (if (bigEndian) 'M' else 'I').code.toByte(); bytes[1] = bytes[0]
        put(2, 42, 2); put(4, 8, 4); put(8, 4, 2)
        listOf(
            0x0103 to 6L,
            0x0112 to orientation.toLong(),
            0x0201 to jpegOffset,
            0x0202 to jpegSize.toLong()
        )
            .forEachIndexed { index, (tag, value) ->
                val entry = 10 + index * 12
                val short = tag == 0x0103 || tag == 0x0112
                put(entry, tag.toLong(), 2); put(entry + 2, if (short) 3 else 4, 2)
                put(entry + 4, 1, 4); put(entry + 8, value, if (short) 2 else 4)
            }
    }

    @Test
    fun readsOnlyHeaderAndMediumJpegInEitherByteOrder() = runTest {
        for (bigEndian in listOf(false, true)) {
            val reads = mutableListOf<Pair<Long, Int>>()
            val jpeg = mediumJpeg()
            val result = SonyArwPreview.read(32_000_000) { offset, length ->
                reads += offset to length
                if (offset == 0L) header(bigEndian, orientation = 6) else jpeg
            }!!
            assertEquals(listOf(0L to 65536, 200_000L to jpeg.size), reads)
            assertContentEquals(jpeg, result.bytes)
            assertEquals(6, result.orientation)
        }
    }

    @Test
    fun embeddedJpegAlreadyInHeaderNeedsNoSecondRead() = runTest {
        val jpeg = mediumJpeg()
        val header = header(jpegOffset = 100).also { jpeg.copyInto(it, 100) }
        var reads = 0
        assertContentEquals(
            jpeg,
            SonyArwPreview.read(32_000_000) { _, _ -> reads++; header }!!.bytes
        )
        assertEquals(1, reads)
    }

    @Test
    fun rejectsOutOfBoundsOrOversizedJpegBeforeReadingIt() = runTest {
        for (header in listOf(
            header(jpegOffset = 0xfffffff0L),
            header(jpegSize = 2 * 1024 * 1024 + 1),
            header(orientation = 9)
        )) {
            var reads = 0
            assertFailsWith<IllegalArgumentException> {
                SonyArwPreview.read(32_000_000) { _, _ -> reads++; header }
            }
            assertEquals(1, reads)
        }
    }

    @Test
    fun malformedHeadersAndTruncatedReadsAreRejected() = runTest {
        assertNull(SonyArwPreview.read(0) { _, _ -> error("Must not read an empty file") })
        assertNull(SonyArwPreview.read(100) { _, count -> ByteArray(count) })
        assertFailsWith<IllegalArgumentException> { SonyArwPreview.read(100) { _, _ -> ByteArray(99) } }
        assertFailsWith<IllegalArgumentException> {
            SonyArwPreview.read(32_000_000) { offset, _ ->
                if (offset == 0L) header() else mediumJpeg().dropLast(1).toByteArray()
            }
        }
        val invalidIfd = header().also { repeat(4) { index -> it[4 + index] = 0xff.toByte() } }
        assertFailsWith<IllegalArgumentException> { SonyArwPreview.read(32_000_000) { _, _ -> invalidIfd } }
    }

    @Test
    fun smallThumbnailIsNotUsedAsTheMediumPreviewAndCancellationPropagates() = runTest {
        assertNull(SonyArwPreview.read(32_000_000) { offset, _ -> if (offset == 0L) header() else testJpeg() })
        assertFailsWith<CancellationException> {
            SonyArwPreview.read(32_000_000) { offset, _ ->
                if (offset == 0L) header() else throw CancellationException("Swiped away")
            }
        }
    }
}
