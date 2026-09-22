package com.sasch.cameragps.sharednew.remote.wifi

import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer

internal fun decodeAndroidCameraThumbnail(bytes: ByteArray): ImageBitmap? {
    if (Build.VERSION.SDK_INT < 28) return null
    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        require(width in 1..8192 && height in 1..8192) { "Invalid camera thumbnail dimensions" }
        val longest = maxOf(width, height)
        if (longest > 640) {
            decoder.setTargetSize(maxOf(1, width * 640 / longest), maxOf(1, height * 640 / longest))
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    return bitmap.asImageBitmap()
}
