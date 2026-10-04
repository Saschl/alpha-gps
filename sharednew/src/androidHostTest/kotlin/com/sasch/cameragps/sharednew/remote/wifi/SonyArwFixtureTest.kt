package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SonyArwFixtureTest {
    @Test
    fun extractsMediumJpegFromRealA6700RawWithoutReadingSensorData() = runTest {
        val prefix =
            checkNotNull(javaClass.getResourceAsStream("/sony/DSC04175-preview-prefix.bin")).use { it.readBytes() }
        val reads = mutableListOf<Pair<Long, Int>>()
        val preview = checkNotNull(SonyArwPreview.read(33_603_584) { offset, length ->
            reads += offset to length
            prefix.copyOfRange(offset.toInt(), offset.toInt() + length)
        })
        assertEquals(listOf(0L to 65536, 196770L to 111289), reads)
        assertEquals(SonyJpegExtent(1616, 1080, 111289), SonyJpeg.inspect(preview.bytes))
        assertEquals(1, preview.orientation)
    }
}
