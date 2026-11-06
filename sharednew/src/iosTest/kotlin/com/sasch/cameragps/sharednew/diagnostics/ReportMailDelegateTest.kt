package com.sasch.cameragps.sharednew.diagnostics

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSSelectorFromString
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class ReportMailDelegateTest {
    @Test
    fun allocatesNativeDelegateAndExposesMailCompletionCallback() {
        val delegate = ReportMailDelegate()

        assertTrue(delegate.respondsToSelector(
            NSSelectorFromString("mailComposeController:didFinishWithResult:error:"),
        ))
    }
}
