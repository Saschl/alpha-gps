package com.sasch.cameragps.sharednew.remote.wifi

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

internal class AndroidCameraImageStore(private val context: Context) : CameraImageStore {
    // Creation returns ownership even if cancelled during blocking storage I/O.
    override suspend fun create(info: SonyImageInfo): CameraImageDestination =
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= 29) createMediaStoreImage(info) else createLegacyImage(
                    info
                )
            }
        }

    private fun createMediaStoreImage(info: SonyImageInfo): CameraImageDestination {
        val resolver = context.contentResolver
        val uri = checkNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, info.filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, info.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Alpha GPS")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })
        )
        val stream = try {
            checkNotNull(resolver.openOutputStream(uri, "w"))
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null); throw failure
        }
        return object : CameraImageDestination {
            private var committed = false
            override suspend fun write(bytes: ByteArray) =
                withContext(Dispatchers.IO) { stream.write(bytes) }

            override suspend fun commit() = withContext(NonCancellable + Dispatchers.IO) {
                stream.close()
                check(
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null
                    ) == 1
                )
                committed = true
            }

            override suspend fun abort() = withContext(NonCancellable + Dispatchers.IO) {
                if (!committed) try {
                    stream.close()
                } finally {
                    resolver.delete(uri, null, null)
                }
                Unit
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createLegacyImage(info: SonyImageInfo): CameraImageDestination {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Alpha GPS"
        )
        check(directory.isDirectory || directory.mkdirs())
        val pending = File.createTempFile(".alpha-gps-", ".pending", directory)
        val stream = try {
            pending.outputStream()
        } catch (failure: Throwable) {
            pending.delete(); throw failure
        }
        return object : CameraImageDestination {
            private var committed = false
            override suspend fun write(bytes: ByteArray) =
                withContext(Dispatchers.IO) { stream.write(bytes) }

            override suspend fun commit() = withContext(NonCancellable + Dispatchers.IO) {
                stream.close()
                val extension = info.filename.substringAfterLast('.', "jpg")
                val target = File.createTempFile(
                    info.filename.substringBeforeLast('.').take(100).padEnd(3, '_') + "-",
                    ".$extension",
                    directory
                )
                try {
                    check(pending.renameTo(target))
                } catch (failure: Throwable) {
                    target.delete(); throw failure
                }
                committed = true
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(target.absolutePath),
                    arrayOf(info.mimeType),
                    null
                )
            }

            override suspend fun abort() = withContext(NonCancellable + Dispatchers.IO) {
                if (!committed) try {
                    stream.close()
                } finally {
                    pending.delete()
                }
                Unit
            }
        }
    }
}
