package com.sasch.cameragps.sharednew.diagnostics

object TroubleshootingEmail {
    const val RECIPIENT = "saschl.ra@web.de"
    const val SUBJECT = "Alpha GPS troubleshooting report"

    fun body(report: TroubleshootingReport): String =
        "Please describe the problem and when it happened:\n\n\n${report.summary}"

    fun shareText(report: TroubleshootingReport): String =
        "To: $RECIPIENT\nSubject: $SUBJECT\n\n${body(report)}"
}
