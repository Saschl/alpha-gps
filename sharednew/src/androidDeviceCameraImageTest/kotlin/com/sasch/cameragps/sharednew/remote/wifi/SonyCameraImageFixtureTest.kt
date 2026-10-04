package com.sasch.cameragps.sharednew.remote.wifi

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.sasch.cameragps.heif.HeifDecoder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class SonyCameraImageFixtureTest {
    @Test
    fun originalsDecodeWithCameraMetadataAndOrientation() {
        SonyImageFixtures.all.forEach { fixture ->
            withFixture(fixture) { file ->
                val exif = if (fixture.format == "heif") {
                    HeifDecoder.readExif(file.path).inputStream().use {
                        ExifInterface(it, ExifInterface.STREAM_TYPE_EXIF_DATA_ONLY)
                    }
                } else ExifInterface(file)
                fixture.verifyMetadata(metadata(exif))
                if (fixture.format == "heif") {
                    fixture.verifyHeifEncoding(file.readBytes())
                    val bitmap = HeifDecoder.decodeFile(file.path, heifDecodeMemoryBudget(), null)
                    try {
                        fixture.verifyImage(bitmap.asImageBitmap())
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    assertEquals(
                        fixture.orientation,
                        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1),
                        fixture.file
                    )
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
                    try {
                        // BitmapFactory returns stored pixels; EXIF carries the display rotation.
                        assertEquals(fixture.width, bitmap.width, fixture.file)
                        assertEquals(fixture.height, bitmap.height, fixture.file)
                        fixture.copy(orientation = 1).verifyImage(bitmap.asImageBitmap())
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun heifConversionPreservesCameraMetadataAndNormalizesRotation() = runBlocking {
        val heifs = SonyImageFixtures.all.filter { it.format == "heif" }
        check(heifs.isNotEmpty()) { "Supply at least one HEIF original" }
        heifs.forEach { fixture ->
            withFixture(fixture) { source ->
                val output = File(source.parentFile, source.name + ".jpg")
                try {
                    convertAndroidHeifToJpeg(source, output)
                    val exif = ExifInterface(output)
                    fixture.verifyMetadata(metadata(exif))
                    assertEquals(
                        ExifInterface.ORIENTATION_NORMAL,
                        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
                    )
                    assertEquals(
                        fixture.displayWidth,
                        exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
                    )
                    assertEquals(
                        fixture.displayHeight,
                        exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
                    )
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(output.path))
                    try {
                        fixture.verifyImage(bitmap.asImageBitmap())
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    output.delete()
                }
            }
        }
    }

    private inline fun withFixture(fixture: SonyImageFixture, action: (File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile(
            "sony-image-",
            ".${fixture.format}",
            instrumentation.targetContext.cacheDir
        )
        try {
            instrumentation.context.assets.open(fixture.file)
                .use { input -> file.outputStream().use(input::copyTo) }
            action(file)
        } catch (failure: Exception) {
            throw AssertionError("${fixture.file}: ${failure.message}", failure)
        } finally {
            file.delete()
        }
    }

    private fun metadata(exif: ExifInterface): Map<String, String> = mapOf(
        "make" to ExifInterface.TAG_MAKE,
        "model" to ExifInterface.TAG_MODEL,
        "lensModel" to ExifInterface.TAG_LENS_MODEL,
        "dateTimeOriginal" to ExifInterface.TAG_DATETIME_ORIGINAL,
        "iso" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ).mapNotNull { (key, tag) -> exif.getAttribute(tag)?.let { key to it } }.toMap()
}
