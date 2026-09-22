package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SonyJpegTest {
    @Test
    fun ignoresThumbnailEndMarkersAndStuffedEntropy() {
        val app = bytes(0xff, 0xe1, 0, 6, 0xff, 0xd9, 0xff, 0xd8)
        val sof = testJpeg().copyOfRange(2, 15)
        val scan = bytes(0xff, 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0)
        val jpeg = bytes(0xff, 0xd8) + app + sof + scan +
            bytes(1, 0xff, 0, 0xd9, 0xff, 0xd0, 2, 0xff, 0xd9)
        assertEquals(SonyJpegExtent(640, 424, jpeg.size), SonyJpeg.inspect(jpeg + ByteArray(34)))
        assertFailsWith<IllegalArgumentException> { SonyJpeg.inspect(jpeg.copyOf(jpeg.size - 2)) }
    }

    @Test
    fun rejectsTruncatedLengthsAndOversizedDimensions() {
        assertFailsWith<IllegalArgumentException> { SonyJpeg.inspect(bytes(0xff, 0xd8, 0xff, 0xe1, 0xff, 0xff)) }
        val tooWide = testJpeg().also { it[9] = 0x20 }
        assertFailsWith<IllegalArgumentException> { SonyJpeg.inspect(tooWide) }
    }

    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
}
