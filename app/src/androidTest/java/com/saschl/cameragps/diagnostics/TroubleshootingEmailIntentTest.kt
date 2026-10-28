package com.saschl.cameragps.diagnostics

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.sasch.cameragps.sharednew.diagnostics.TroubleshootingReport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TroubleshootingEmailIntentTest {
    @Test
    fun emailPrefillsRecipientAndPreservesZipReadPermission() {
        val uri = Uri.parse("content://com.saschl.cameragps.troubleshooting/report.zip")
        val report = TroubleshootingReport("App version: 1.0", "Log contents")

        val intent = troubleshootingEmailIntent(uri, report)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        assertArrayEquals(
            arrayOf("saschl.ra@web.de"),
            intent.getStringArrayExtra(Intent.EXTRA_EMAIL)
        )
        assertEquals(
            "Alpha GPS troubleshooting report",
            intent.getStringExtra(Intent.EXTRA_SUBJECT)
        )
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().contains(report.summary))
        assertEquals(
            uri,
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        )
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
