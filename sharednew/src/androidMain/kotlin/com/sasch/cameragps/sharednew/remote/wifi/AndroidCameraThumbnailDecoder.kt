package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.sasch.cameragps.heif.HeifDecoder

internal fun decodeAndroidCameraThumbnail(bytes: ByteArray): ImageBitmap =
    HeifDecoder.decodeThumbnail(bytes, heifDecodeMemoryBudget()).asImageBitmap()
