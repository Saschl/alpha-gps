package com.sasch.cameragps.sharednew.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsNewStateTest {
    @Test
    fun existingInstallWithoutTrackingSeesNotesUntilDismissed() {
        var stored: String? = null
        fun launch() =
            WhatsNewState("v1.6.2", ReleasePlatform.Android, stored, false) { stored = it }

        val state = launch()
        state.initialize()
        assertTrue(state.pending)
        assertNull(stored)
        assertTrue(launch().pending) // Interrupted before dismissal: retry next launch.
        state.dismiss()
        assertFalse(state.pending)
        assertEquals("1.6.2", stored)
        assertFalse(launch().pending)
    }

    @Test
    fun freshInstallEstablishesBaselineWithoutShowingNotes() {
        var stored: String? = null
        val state = WhatsNewState("1.6.2", ReleasePlatform.Ios, stored, true) { stored = it }
        state.initialize()
        assertFalse(state.pending)
        assertEquals("1.6.2", stored)
        assertFalse(WhatsNewState("1.6.2", ReleasePlatform.Ios, stored, false) {}.pending)
    }

    @Test
    fun skippedVersionsShowCurrentRelease() {
        val state = WhatsNewState("1.6.2", ReleasePlatform.Android, "1.4.0", false) {}
        assertTrue(state.pending)
        assertEquals("1.6.2", state.release?.version)
    }

    @Test
    fun downgradeDoesNotShowOrOverwriteNewerBaselineEvenFromSettings() {
        var stored = "1.7.0"
        val state = WhatsNewState("1.6.2", ReleasePlatform.Ios, stored, false) { stored = it }
        state.initialize()
        state.dismiss()
        assertFalse(state.pending)
        assertEquals("1.7.0", stored)
    }

    @Test
    fun releaseWithoutNotesDoesNotReuseOldContent() {
        var stored = "1.6.2"
        val state = WhatsNewState("1.6.3", ReleasePlatform.Android, stored, false) { stored = it }
        state.initialize()
        assertNull(state.release)
        assertFalse(state.pending)
        assertEquals("1.6.3", stored)
    }

    @Test
    fun versionsCompareNumericallyAndIgnoreLeadingVAndTrailingZero() {
        assertTrue(WhatsNewPolicy.isNewer("1.10.0", "1.9.9"))
        assertFalse(WhatsNewPolicy.isNewer("1.9.9", "1.10.0"))
        assertFalse(WhatsNewPolicy.isNewer("v1.6.2", "1.6.2.0"))
        assertFalse(WhatsNewPolicy.isNewer("", "1.6.2"))
        assertFalse(WhatsNewPolicy.isNewer("1.6.2-beta", null))
    }

    @Test
    fun iosPairingNotesAreOnlyIncludedOnIos() {
        val android = ReleaseNotesCatalog.forVersion("v1.6.2", ReleasePlatform.Android)!!
        val ios = ReleaseNotesCatalog.forVersion("1.6.2", ReleasePlatform.Ios)!!
        assertEquals(android.highlights.first(), ios.highlights.first())
        assertFalse(android.highlights.contains(ios.highlights.last()))
        assertFalse(ios.highlights.contains(android.highlights.last()))
    }
}
