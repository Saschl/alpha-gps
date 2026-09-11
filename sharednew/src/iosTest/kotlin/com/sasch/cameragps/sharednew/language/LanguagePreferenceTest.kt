package com.sasch.cameragps.sharednew.language

import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguagePreferenceTest {

    private fun withDefaults(block: (NSUserDefaults, String) -> Unit) {
        val suite = "language-test-${NSUUID().UUIDString}"
        val defaults = NSUserDefaults(suiteName = suite)
        try {
            block(defaults, suite)
        } finally {
            defaults.removePersistentDomainForName(suite)
        }
    }

    @Test
    fun selectionIsPersistedAndReadBackByANewInstance() = withDefaults { defaults, suite ->
        val language = SupportedLanguages.entries.first()
        val preference = LanguagePreference(defaults, suite)
        assertNull(preference.selected.value)

        preference.select(language)
        assertEquals(language, preference.selected.value)
        assertEquals(language, LanguagePreference(defaults, suite).selected.value)

        preference.select(null)
        assertNull(preference.selected.value)
        assertNull(LanguagePreference(defaults, suite).selected.value)
    }

    @Test
    fun refreshPicksUpALanguageChangedBehindOurBack() = withDefaults { defaults, suite ->
        val language = SupportedLanguages.entries.first()
        val preference = LanguagePreference(defaults, suite)

        // What the iOS Settings app does while we are backgrounded.
        defaults.setObject(listOf(language.tag), forKey = "AppleLanguages")
        assertNull(preference.selected.value)

        preference.refresh()
        assertEquals(language, preference.selected.value)
    }

    @Test
    fun anUnsupportedStoredTagReadsAsFollowTheSystem() = withDefaults { defaults, suite ->
        defaults.setObject(listOf("zz-ZZ"), forKey = "AppleLanguages")
        assertNull(LanguagePreference(defaults, suite).selected.value)
    }
}
