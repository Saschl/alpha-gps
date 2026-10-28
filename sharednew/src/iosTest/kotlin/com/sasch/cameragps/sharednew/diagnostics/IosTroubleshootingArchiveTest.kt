package com.sasch.cameragps.sharednew.diagnostics

import com.sasch.cameragps.sharednew.bluetooth.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalForeignApi::class)
class IosTroubleshootingArchiveTest {
    @Test
    fun archiveSurvivesCoordinationAndContainsBothFilesWithNoStagingDirectory() = runTest {
        val files = NSFileManager.defaultManager
        val archive = withContext(Dispatchers.IO) {
            createIosTroubleshootingArchive(TroubleshootingReport("Kamera ü 📷", "A retained log\n"))
        }
        try {
            val path = assertNotNull(archive.path)
            val bytes = assertNotNull(files.contentsAtPath(path)).toByteArray()
            assertEquals(
                listOf(0x50.toByte(), 0x4b.toByte(), 0x03.toByte(), 0x04.toByte()),
                bytes.take(4)
            )
            // ZIP central-directory names are uncompressed, regardless of Foundation's compression choice.
            val directory = bytes.decodeToString()
            assertContains(directory, "summary.txt")
            assertContains(directory, "logs.txt")
            assertContains(directory, "PK\u0005\u0006")
            val staging = path.substringBeforeLast('/') + "/" +
                    path.substringAfterLast('/').removePrefix("alpha-gps-report-")
                        .removeSuffix(".zip")
            assertFalse(files.fileExistsAtPath(staging))
        } finally {
            files.removeItemAtURL(archive, error = null)
        }
    }
}
