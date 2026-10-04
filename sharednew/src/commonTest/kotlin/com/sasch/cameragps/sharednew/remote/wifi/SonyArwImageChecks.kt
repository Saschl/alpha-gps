package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.assertEquals

internal suspend fun verifySonyArwImage(prefix: ByteArray) {
    val preview = checkNotNull(SonyArwPreview.read(33_603_584) { offset, length ->
        prefix.copyOfRange(offset.toInt(), offset.toInt() + length)
    })
    val source = preview.decode()
    assertEquals(1616, source.width)
    assertEquals(1080, source.height)
    val pixel = IntArray(1)
    source.readPixels(pixel, startX = 300, startY = 200, width = 1, height = 1)
    // Each transform must move the same source pixel, including mirrored orientations.
    val positions = listOf(
        300 to 200, 1315 to 200, 1315 to 879, 300 to 879,
        200 to 300, 879 to 300, 879 to 1315, 200 to 1315
    )
    positions.forEachIndexed { index, (x, y) ->
        val image = preview.copy(orientation = index + 1).decode()
        assertEquals(if (index >= 4) 1080 else 1616, image.width)
        assertEquals(if (index >= 4) 1616 else 1080, image.height)
        val transformed = IntArray(1)
        image.readPixels(transformed, startX = x, startY = y, width = 1, height = 1)
        assertEquals(pixel[0], transformed[0], "Orientation ${index + 1}")
    }
}
