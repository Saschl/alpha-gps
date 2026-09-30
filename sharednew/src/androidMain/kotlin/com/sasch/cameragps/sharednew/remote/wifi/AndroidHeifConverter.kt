package com.sasch.cameragps.sharednew.remote.wifi

import android.graphics.Bitmap
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.sasch.cameragps.heif.HeifDecoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal suspend fun convertAndroidHeifToJpeg(source: File, target: File) = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    var stage = "decoding HEIF"
    try {
        val job = currentCoroutineContext()[Job]
        val bitmap = HeifDecoder.decodeFile(source.absolutePath, heifDecodeMemoryBudget()) {
            job?.isActive == false
        }
        try {
            currentCoroutineContext().ensureActive()
            stage = "encoding JPEG"
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            currentCoroutineContext().ensureActive()
            stage = "copying EXIF"
            copyPhotoExif(source, target, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w("CameraHeif", "JPEG conversion failed while $stage", failure)
        throw failure
    } catch (failure: OutOfMemoryError) {
        throw IOException("Insufficient memory for HEIF conversion", failure)
    }
}

private fun copyPhotoExif(source: File, target: File, width: Int, height: Int) {
    val exif = HeifDecoder.readExif(source.absolutePath)
    val original = if (exif.isEmpty()) null else exif.inputStream().use {
        ExifInterface(it, ExifInterface.STREAM_TYPE_EXIF_DATA_ONLY)
    }
    val jpeg = ExifInterface(target)
    for (tag in PHOTO_EXIF_TAGS) original?.getAttribute(tag)?.let { jpeg.setAttribute(tag, it) }
    // libheif applies the HEIF rotation and mirroring to the pixels.
    jpeg.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
    jpeg.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
    jpeg.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
    jpeg.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
    jpeg.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
    jpeg.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
    jpeg.saveAttributes()
}

private val PHOTO_EXIF_TAGS = listOf(
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
    ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE,
    ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
)

internal fun heifDecodeMemoryBudget(): Long =
    (Runtime.getRuntime().maxMemory() * 2).coerceIn(256L * 1024 * 1024, 1024L * 1024 * 1024)
