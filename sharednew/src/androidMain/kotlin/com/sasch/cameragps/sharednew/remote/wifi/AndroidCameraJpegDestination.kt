package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

internal class AndroidCameraJpegDestination private constructor(
    private val source: File,
    private val stream: OutputStream,
    private val info: SonyImageInfo,
    private val store: CameraImageStore,
    private val convert: suspend (File, File) -> Unit,
) : CameraImageDestination {
    private var jpeg: File? = null
    private var output: CameraImageDestination? = null

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { stream.write(bytes) }

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        stream.close()
        val target = File.createTempFile("alpha-jpeg-", ".jpg", source.parentFile).also { jpeg = it }
        convert(source, target)
        currentCoroutineContext().ensureActive()
        require(target.length() > 0)
        val convertedInfo = info.copy(
            filename = info.filename.substringBeforeLast('.') + ".jpg",
            mimeType = "image/jpeg", size = target.length()
        )
        // Retain ownership if cancellation happens while storage is being created.
        val destination = withContext(NonCancellable) { store.create(convertedInfo).also { output = it } }
        target.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                destination.write(if (count == buffer.size) buffer else buffer.copyOf(count))
            }
        }
        destination.prepare()
        source.delete()
        target.delete()
        Unit
    }

    override suspend fun commit() = checkNotNull(output).commit()

    override suspend fun abort() = withContext(NonCancellable + Dispatchers.IO) {
        try {
            output?.abort()
        } finally {
            try { stream.close() } finally {
                source.delete()
                jpeg?.delete()
            }
        }
        Unit
    }

    companion object {
        suspend fun create(
            directory: File, info: SonyImageInfo, store: CameraImageStore,
            convert: suspend (File, File) -> Unit,
        ): CameraImageDestination = withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                val source = File.createTempFile("alpha-heif-", ".hif", directory)
                try {
                    AndroidCameraJpegDestination(source, source.outputStream(), info, store, convert)
                } catch (failure: Throwable) {
                    source.delete()
                    throw failure
                }
            }
        }
    }
}
