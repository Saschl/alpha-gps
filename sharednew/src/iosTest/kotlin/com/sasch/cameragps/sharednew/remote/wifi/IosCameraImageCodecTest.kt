@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.cinterop.*
import platform.Foundation.*
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class IosCameraImageCodecTest {

    @Test
    fun decodesTenBit422ThumbnailsAndAppliesRotation() {
        verifyRed(checkNotNull(decodeIosCameraThumbnail(HeifTestFixtures.original)), 128, 64)
        verifyRed(checkNotNull(decodeIosCameraThumbnail(HeifTestFixtures.rotated)), 64, 128)
    }

    @Test
    fun convertsFullResolutionAndPreservesRotation() = withFixture { path ->
        val output = "$path.jpg"
        try {
            convertIosCameraHeifToJpeg(path, output)
            val data = checkNotNull(platform.Foundation.NSData.dataWithContentsOfFile(output))
            val bytes = data.bytes!!.reinterpret<kotlinx.cinterop.ByteVar>().readBytes(data.length.toInt())
            verifyRed(checkNotNull(decodeIosCameraThumbnail(bytes)), 64, 128)
            assertTrue(bytes.decodeToString().contains("Alpha GPS test"))
        } finally {
            unlink(output)
        }
    }

    @Test
    fun rejectsMalformedInput() {
        kotlin.test.assertNull(decodeIosCameraThumbnail(byteArrayOf(0, 1, 2)))
        kotlin.test.assertNull(decodeIosCameraThumbnail(ByteArray(0)))
        assertFails { convertIosCameraHeifToJpeg("/missing/image.hif", "/missing/image.jpg") }
    }

    private fun verifyRed(image: ImageBitmap, width: Int, height: Int) {
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        val pixels = IntArray(width * height)
        image.readPixels(pixels)
        val center = pixels[height / 2 * width + width / 2]
        assertEquals(255, center ushr 24)
        assertTrue((center ushr 16 and 255) > 240)
        assertTrue((center ushr 8 and 255) < 15)
        assertTrue((center and 255) < 15)
    }

    private fun withFixture(action: (String) -> Unit) {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".hif"
        try {
            val file = checkNotNull(fopen(path, "wb"))
            try {
                HeifTestFixtures.rotated.usePinned { bytes ->
                    assertEquals(bytes.get().size.toULong(), fwrite(bytes.addressOf(0), 1u, bytes.get().size.toULong(), file))
                }
            } finally {
                fclose(file)
            }
            action(path)
        } finally {
            unlink(path)
        }
    }
}
