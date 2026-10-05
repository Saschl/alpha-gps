package com.sasch.cameragps.sharednew.remote.wifi

import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals

class SonyLiveViewImageTest {
    @Test
    fun paddedFrameDecodesWithTheIosImageDecoder() {
        val frame = SonyLiveViewFrameDecoder().feed(previewFrameFixture()).single()
        val image = frame.imageBytes.decodeToImageBitmap()
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        val pixels = IntArray(4)
        image.readPixels(pixels)
        assertEquals(4, pixels.count { (it ushr 24) == 255 })
    }
}
