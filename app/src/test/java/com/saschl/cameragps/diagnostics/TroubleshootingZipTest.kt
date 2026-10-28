package com.saschl.cameragps.diagnostics

import com.sasch.cameragps.sharednew.diagnostics.TroubleshootingReport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class TroubleshootingZipTest {
    @Test
    fun archiveRoundTripsBothUtf8FilesIncludingAllLogs() {
        val file = Files.createTempFile("troubleshooting-test", ".zip").toFile()
        val report =
            TroubleshootingReport("Kamera ü 📷", (1..5_000).joinToString("\n") { "Log $it" })
        try {
            writeTroubleshootingZip(file, report)
            ZipFile(file).use { zip ->
                val extracted = zip.entries().asSequence().associate { entry ->
                    entry.name to zip.getInputStream(entry).bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                }
                assertEquals(report.files, extracted)
            }
        } finally {
            file.delete()
        }
    }
}
