@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import platform.Foundation.*
import platform.Photos.*
import platform.posix.*

internal suspend fun requestIosPhotoSavePermission(): Boolean {
    val status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
    if (status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) return true
    if (status != PHAuthorizationStatusNotDetermined) return false
    val result = Channel<Boolean>(1)
    try {
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) {
            result.trySend(it == PHAuthorizationStatusAuthorized || it == PHAuthorizationStatusLimited)
        }
        return result.receive()
    } finally {
        result.close()
    }
}

internal class IosCameraImageStore : CameraImageStore {
    override val canConvertHeif = true
    override suspend fun create(info: SonyImageInfo): CameraImageDestination = create(info, false)
    override suspend fun createJpeg(info: SonyImageInfo): CameraImageDestination = create(info, true)

    private suspend fun create(info: SonyImageInfo, jpeg: Boolean): CameraImageDestination =
        withContext(NonCancellable) {
            withContext(Dispatchers.Default) {
                val filename = if (jpeg) info.filename.substringBeforeLast('.') + ".jpg" else info.filename
                val stem = NSTemporaryDirectory() + "camera-" + NSUUID().UUIDString
                val sourcePath = stem + "." + info.filename.substringAfterLast('.', "image")
                val outputPath = if (jpeg) "$stem.converted.jpg" else sourcePath
                val file = checkNotNull(fopen(sourcePath, "wb")) { "Could not create download file" }
                object : CameraImageDestination {
                    private var stream: CPointer<FILE>? = file
                    private var prepared = false
                    private var committed = false

                    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.Default) {
                        val output = checkNotNull(stream)
                        if (bytes.isNotEmpty()) bytes.usePinned {
                            check(fwrite(it.addressOf(0), 1u, bytes.size.toULong(), output) == bytes.size.toULong()) {
                                "Could not write download file"
                            }
                        }
                        Unit
                    }

                    override suspend fun prepare() = withContext(Dispatchers.Default) {
                        val output = checkNotNull(stream)
                        stream = null
                        check(fclose(output) == 0) { "Could not finish download file" }
                        ensureActive()
                        if (jpeg) convertIosCameraHeifToJpeg(sourcePath, outputPath)
                        ensureActive()
                        prepared = true
                    }

                    override suspend fun commit() {
                        check(prepared)
                        val result = Channel<Boolean>(1)
                        try {
                            PHPhotoLibrary.sharedPhotoLibrary().performChanges({
                                val request = PHAssetCreationRequest.creationRequestForAsset()
                                val options = PHAssetResourceCreationOptions().apply { originalFilename = filename }
                                request.addResourceWithType(PHAssetResourceTypePhoto, NSURL.fileURLWithPath(outputPath), options)
                            }) { success, _ -> result.trySend(success) }
                            check(result.receive()) { "Could not save image to Photos" }
                            committed = true
                        } finally {
                            result.close()
                            if (committed) removeFiles()
                        }
                    }

                    override suspend fun abort() = withContext(NonCancellable + Dispatchers.Default) {
                        stream?.let { fclose(it) }
                        stream = null
                        removeFiles()
                    }

                    private fun removeFiles() {
                        unlink(sourcePath)
                        if (outputPath != sourcePath) unlink(outputPath)
                    }
                }
            }
        }
}
