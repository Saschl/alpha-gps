package com.sasch.cameragps.sharednew.diagnostics

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TroubleshootingEmailTest {
    @Test
    fun emailBodyContainsSummaryWhileFullLogsStayInTheAttachment() {
        val report = TroubleshootingReport("App version: 1.0", "Verbose log contents")

        val body = TroubleshootingEmail.body(report)

        assertContains(body, report.summary)
        assertFalse(report.logs in body)
    }

    @Test
    fun fallbackShareKeepsRecipientAndSubjectVisibleWithoutMailConfiguration() {
        val report = TroubleshootingReport("App version: 1.0", "Verbose log contents")

        val text = TroubleshootingEmail.shareText(report)

        assertContains(text, "To: saschl.ra@web.de")
        assertContains(text, "Subject: Alpha GPS troubleshooting report")
        assertContains(text, report.summary)
        assertFalse(report.logs in text)
    }
}
