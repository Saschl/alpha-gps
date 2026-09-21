package com.sasch.cameragps.sharednew.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportPolicyTest {

    // --- route ---------------------------------------------------------------

    @Test
    fun verboseAndDebugNeverLeaveTheDevice() {
        assertEquals(
            CrashReportRoute.Ignore,
            CrashReportPolicy.route(CrashReportPolicy.PRIORITY_VERBOSE)
        )
        assertEquals(
            CrashReportRoute.Ignore,
            CrashReportPolicy.route(CrashReportPolicy.PRIORITY_DEBUG)
        )
    }

    @Test
    fun infoAndWarnBecomeBreadcrumbs() {
        assertEquals(
            CrashReportRoute.Breadcrumb,
            CrashReportPolicy.route(CrashReportPolicy.PRIORITY_INFO)
        )
        assertEquals(
            CrashReportRoute.Breadcrumb,
            CrashReportPolicy.route(CrashReportPolicy.PRIORITY_WARN)
        )
    }

    @Test
    fun errorAndAboveBecomeEvents() {
        assertEquals(
            CrashReportRoute.Event,
            CrashReportPolicy.route(CrashReportPolicy.PRIORITY_ERROR)
        )
        // Timber/Log also know ASSERT (7); it must not fall back to a breadcrumb.
        assertEquals(CrashReportRoute.Event, CrashReportPolicy.route(7))
    }

    @Test
    fun unknownLowPrioritiesAreIgnored() {
        assertEquals(CrashReportRoute.Ignore, CrashReportPolicy.route(0))
        assertEquals(CrashReportRoute.Ignore, CrashReportPolicy.route(-1))
    }

    // --- shouldSendAsLog ------------------------------------------------------

    @Test
    fun infoAndAboveAlsoBecomeStructuredLogs() {
        assertTrue(CrashReportPolicy.shouldSendAsLog(CrashReportPolicy.PRIORITY_INFO))
        assertTrue(CrashReportPolicy.shouldSendAsLog(CrashReportPolicy.PRIORITY_WARN))
        assertTrue(CrashReportPolicy.shouldSendAsLog(CrashReportPolicy.PRIORITY_ERROR))
    }

    @Test
    fun verboseAndDebugAreNotSentAsLogs() {
        assertFalse(CrashReportPolicy.shouldSendAsLog(CrashReportPolicy.PRIORITY_VERBOSE))
        assertFalse(CrashReportPolicy.shouldSendAsLog(CrashReportPolicy.PRIORITY_DEBUG))
    }

    @Test
    fun logsAreOrthogonalToRouting() {
        // An ERROR is an issue AND a log line; an INFO is a breadcrumb AND a log
        // line. Regression guard: making one an "else" branch of the other would
        // silently empty either the Logs view or the Issues stream.
        assertEquals(
            CrashReportRoute.Event,
            CrashReportPolicy.route(CrashReportPolicy.PRIORITY_ERROR)
        )
        assertTrue(CrashReportPolicy.shouldSendAsLog(CrashReportPolicy.PRIORITY_ERROR))
        assertEquals(
            CrashReportRoute.Breadcrumb,
            CrashReportPolicy.route(CrashReportPolicy.PRIORITY_INFO)
        )
        assertTrue(CrashReportPolicy.shouldSendAsLog(CrashReportPolicy.PRIORITY_INFO))
    }

    // --- redact --------------------------------------------------------------

    @Test
    fun macAddressesAreReplaced() {
        assertEquals(
            "Connected to XX:XX:XX:XX:XX:XX",
            CrashReportPolicy.redact("Connected to A4:5E:60:1B:2C:3D")
        )
    }

    @Test
    fun lowercaseAndDashSeparatedMacsAreReplaced() {
        assertEquals(
            "peer XX:XX:XX:XX:XX:XX gone",
            CrashReportPolicy.redact("peer a4:5e:60:1b:2c:3d gone")
        )
        assertEquals(
            "peer XX:XX:XX:XX:XX:XX gone",
            CrashReportPolicy.redact("peer A4-5E-60-1B-2C-3D gone")
        )
    }

    @Test
    fun everyMacInALineIsReplaced() {
        assertEquals(
            "XX:XX:XX:XX:XX:XX -> XX:XX:XX:XX:XX:XX",
            CrashReportPolicy.redact("A4:5E:60:1B:2C:3D -> 11:22:33:44:55:66")
        )
    }

    @Test
    fun ordinaryTextSurvivesUntouched() {
        val message = "handshake step 3 of 4 took 1250 ms (session 12:30)"
        assertEquals(message, CrashReportPolicy.redact(message))
    }

    // --- consent gating ------------------------------------------------------

    @Test
    fun reportingStartsOnlyAfterTheUserAnsweredTheDialog() {
        assertTrue(
            CrashReportPolicy.shouldInitialize(
                enabled = true,
                consentDialogDismissed = true
            )
        )
        // The switch defaults to off; an untouched default must never opt someone in.
        assertFalse(
            CrashReportPolicy.shouldInitialize(
                enabled = true,
                consentDialogDismissed = false
            )
        )
        assertFalse(
            CrashReportPolicy.shouldInitialize(
                enabled = false,
                consentDialogDismissed = true
            )
        )
        assertFalse(
            CrashReportPolicy.shouldInitialize(
                enabled = false,
                consentDialogDismissed = false
            )
        )
    }

    @Test
    fun consentDialogIsShownOnceAndOnlyWhereACrashReporterExists() {
        assertTrue(
            CrashReportPolicy.shouldShowConsentDialog(
                available = true,
                consentDialogDismissed = false
            )
        )
        assertFalse(
            CrashReportPolicy.shouldShowConsentDialog(
                available = true,
                consentDialogDismissed = true
            )
        )
        // foss (F-Droid) ships no crash reporter, so the dialog never appears.
        assertFalse(
            CrashReportPolicy.shouldShowConsentDialog(
                available = false,
                consentDialogDismissed = false
            )
        )
    }
}
