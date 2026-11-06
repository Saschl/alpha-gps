package com.sasch.cameragps.sharednew.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportingLifecycleTest {
    @Test
    fun launchWithoutConsentDoesNotStartOrStopTheSdk() {
        val calls = mutableListOf<String>()
        val lifecycle = recordingLifecycle(calls)

        lifecycle.update(enabled = false, consentDialogDismissed = false)
        lifecycle.update(enabled = true, consentDialogDismissed = false)
        lifecycle.update(enabled = false, consentDialogDismissed = true)

        assertFalse(lifecycle.isStarted)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun repeatedLaunchAndSettingsUpdatesDoNotDuplicateIntegrations() {
        val calls = mutableListOf<String>()
        val lifecycle = recordingLifecycle(calls)

        repeat(3) {
            lifecycle.update(enabled = true, consentDialogDismissed = true)
        }

        assertTrue(lifecycle.isStarted)
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun disablingStopsImmediatelyAndCanBeReenabledRepeatedly() {
        val calls = mutableListOf<String>()
        val lifecycle = recordingLifecycle(calls)

        repeat(3) {
            lifecycle.update(enabled = true, consentDialogDismissed = true)
            assertTrue(lifecycle.isStarted)

            lifecycle.update(enabled = false, consentDialogDismissed = true)
            assertFalse(lifecycle.isStarted)
            lifecycle.update(enabled = false, consentDialogDismissed = true)
        }

        assertEquals(List(3) { listOf("start", "stop") }.flatten(), calls)
    }

    @Test
    fun losingConsentStopsAnAlreadyRunningSdk() {
        val calls = mutableListOf<String>()
        val lifecycle = recordingLifecycle(calls)
        lifecycle.update(enabled = true, consentDialogDismissed = true)

        lifecycle.update(enabled = true, consentDialogDismissed = false)

        assertFalse(lifecycle.isStarted)
        assertEquals(listOf("start", "stop"), calls)
    }

    @Test
    fun failedInitializationDoesNotPreventRetry() {
        var attempts = 0
        val lifecycle = CrashReportingLifecycle(
            start = {
                attempts++
                check(attempts > 1)
            },
            stop = {},
        )

        assertFailsWith<IllegalStateException> {
            lifecycle.update(enabled = true, consentDialogDismissed = true)
        }
        assertFalse(lifecycle.isStarted)

        lifecycle.update(enabled = true, consentDialogDismissed = true)
        assertTrue(lifecycle.isStarted)
        assertEquals(2, attempts)
    }

    private fun recordingLifecycle(calls: MutableList<String>) = CrashReportingLifecycle(
        start = { calls += "start" },
        stop = { calls += "stop" },
    )
}
