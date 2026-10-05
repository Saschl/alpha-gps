@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.ImageIO.*
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals

class SonyCameraImageFixtureTest {
    @Test
    fun originalsDecodeWithCameraMetadataAndOrientation() {
        SonyImageFixtures.all.forEach { fixture ->
            val path = "${SonyImageFixtures.directory}/${fixture.file}"
            val properties = properties(path)
            assertEquals(
                fixture.width,
                (properties.value(kCGImagePropertyPixelWidth) as NSNumber).intValue,
                fixture.file
            )
            assertEquals(
                fixture.height,
                (properties.value(kCGImagePropertyPixelHeight) as NSNumber).intValue,
                fixture.file
            )
            assertEquals(
                fixture.orientation,
                (properties.value(kCGImagePropertyOrientation) as? NSNumber)?.intValue ?: 1,
                fixture.file
            )
            fixture.verifyMetadata(metadata(properties))
            val bytes = readBytes(path)
            if (fixture.format == "heif") fixture.verifyHeifEncoding(bytes)
            fixture.verifyImage(checkNotNull(decodeIosCameraThumbnail(bytes)), 640)
            fixture.verifyImage(checkNotNull(decodeIosCameraPreview(bytes)), 2048)
        }
    }

    @Test
    fun heifConversionPreservesCameraMetadataAndDisplayOrientation() {
        val heifs = SonyImageFixtures.all.filter { it.format == "heif" }
        check(heifs.isNotEmpty()) { "Supply at least one HEIF original" }
        heifs.forEach { fixture ->
            val output = NSTemporaryDirectory() + NSUUID().UUIDString + ".jpg"
            try {
                convertIosCameraHeifToJpeg("${SonyImageFixtures.directory}/${fixture.file}", output)
                val properties = properties(output)
                fixture.verifyMetadata(metadata(properties))
                val width = (properties.value(kCGImagePropertyPixelWidth) as NSNumber).intValue
                val height = (properties.value(kCGImagePropertyPixelHeight) as NSNumber).intValue
                val orientation =
                    (properties.value(kCGImagePropertyOrientation) as? NSNumber)?.intValue ?: 1
                assertEquals(
                    fixture.displayWidth,
                    if (orientation in 5..8) height else width,
                    fixture.file
                )
                assertEquals(
                    fixture.displayHeight,
                    if (orientation in 5..8) width else height,
                    fixture.file
                )
                fixture.verifyImage(checkNotNull(decodeIosCameraPreview(readBytes(output))), 2048)
            } finally {
                unlink(output)
            }
        }
    }

    private fun readBytes(path: String): ByteArray {
        val data = checkNotNull(NSData.dataWithContentsOfFile(path)) { "Missing fixture: $path" }
        return checkNotNull(data.bytes).reinterpret<ByteVar>().readBytes(data.length.toInt())
    }

    private fun properties(path: String): Map<Any?, *> {
        val url = path.encodeToByteArray().usePinned {
            checkNotNull(
                CFURLCreateFromFileSystemRepresentation(
                    null,
                    it.addressOf(0).reinterpret(),
                    it.get().size.toLong(),
                    false
                )
            )
        }
        try {
            val source = checkNotNull(CGImageSourceCreateWithURL(url, null))
            try {
                val properties = checkNotNull(CGImageSourceCopyPropertiesAtIndex(source, 0u, null))
                try {
                    @Suppress("UNCHECKED_CAST")
                    return (CFBridgingRelease(CFRetain(properties)) as Map<Any?, *>).toMap()
                } finally {
                    CFRelease(properties)
                }
            } finally {
                CFRelease(source)
            }
        } finally {
            CFRelease(url)
        }
    }

    private fun metadata(properties: Map<Any?, *>): Map<String, String> {
        val tiff =
            properties.value(kCGImagePropertyTIFFDictionary) as? Map<*, *> ?: emptyMap<Any, Any>()
        val exif =
            properties.value(kCGImagePropertyExifDictionary) as? Map<*, *> ?: emptyMap<Any, Any>()
        return mapOf(
            "make" to tiff.value(kCGImagePropertyTIFFMake),
            "model" to tiff.value(kCGImagePropertyTIFFModel),
            "lensModel" to exif.value(kCGImagePropertyExifLensModel),
            "dateTimeOriginal" to exif.value(kCGImagePropertyExifDateTimeOriginal),
            "iso" to (exif.value(kCGImagePropertyExifISOSpeedRatings) as? List<*>)?.firstOrNull(),
        ).mapNotNull { (key, value) -> value?.let { key to it.toString() } }.toMap()
    }

    private fun Map<*, *>.value(key: CFStringRef?): Any? =
        get(CFBridgingRelease(CFRetain(key)) as String)
}
