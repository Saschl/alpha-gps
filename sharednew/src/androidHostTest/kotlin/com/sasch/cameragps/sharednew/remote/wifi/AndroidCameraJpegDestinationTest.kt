package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCameraJpegDestinationTest {
    private val info = SonyImageInfo(4, "DSC.HIF", "image/heif", "20260924T100000")

    private class Destination : CameraImageDestination {
        val bytes = mutableListOf<Byte>()
        var committed = false
        var aborted = false
        override suspend fun write(bytes: ByteArray) { this.bytes += bytes.toList() }
        override suspend fun commit() { committed = true }
        override suspend fun abort() { aborted = true }
    }

    @Test
    fun publishesOnlyConvertedBytesWithJpegMetadataAndRemovesTemporaryFiles() = runTest {
        val directory = Files.createTempDirectory("jpeg-test").toFile()
        try {
            val original = byteArrayOf(1, 2, 3, 4)
            val jpeg = ByteArray(150_000) { (it % 251).toByte() }
            val output = Destination()
            var savedInfo: SonyImageInfo? = null
            val destination = AndroidCameraJpegDestination.create(directory, info,
                CameraImageStore { savedInfo = it; output }) { source, target ->
                assertContentEquals(original, source.readBytes())
                target.writeBytes(jpeg)
            }
            destination.write(original)
            assertEquals(null, savedInfo)
            destination.prepare()
            assertEquals(info.copy(size = jpeg.size.toLong(), filename = "DSC.jpg", mimeType = "image/jpeg"), savedInfo)
            assertContentEquals(jpeg, output.bytes.toByteArray())
            assertTrue(directory.listFiles()!!.isEmpty())
            assertFalse(output.committed)
            destination.commit()
            assertTrue(output.committed)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun failedConversionCreatesNoGalleryEntryAndAbortRemovesBothTemporaryFiles() = runTest {
        val directory = Files.createTempDirectory("jpeg-test").toFile()
        try {
            val destination = AndroidCameraJpegDestination.create(directory, info,
                CameraImageStore { error("Must not publish") }) { _, target ->
                target.writeBytes(byteArrayOf(1))
                error("Unsupported HEIF")
            }
            destination.write(byteArrayOf(1, 2, 3, 4))
            assertFailsWith<IllegalStateException> { destination.prepare() }
            destination.abort()
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cancellationDuringConversionCleansUpWithoutPublishing() = runTest {
        val directory = Files.createTempDirectory("jpeg-test").toFile()
        try {
            val converting = CompletableDeferred<Unit>()
            val destination = AndroidCameraJpegDestination.create(directory, info,
                CameraImageStore { error("Must not publish") }) { _, _ ->
                converting.complete(Unit)
                awaitCancellation()
            }
            val job = launch {
                try {
                    destination.write(byteArrayOf(1, 2, 3, 4))
                    destination.prepare()
                    destination.commit()
                } finally { destination.abort() }
            }
            converting.await()
            job.cancel()
            job.join()
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cancellationWhileCreatingGalleryEntryStillAbortsTheOwnedDestination() = runTest {
        val directory = Files.createTempDirectory("jpeg-test").toFile()
        try {
            val creating = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val output = Destination()
            val destination = AndroidCameraJpegDestination.create(directory, info,
                CameraImageStore {
                    creating.complete(Unit)
                    resume.await()
                    output
                }) { _, target -> target.writeBytes(byteArrayOf(1)) }
            val job = launch {
                try {
                    destination.prepare()
                    destination.commit()
                } finally { destination.abort() }
            }
            creating.await()
            job.cancel()
            resume.complete(Unit)
            job.join()
            assertTrue(output.aborted)
            assertFalse(output.committed)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
