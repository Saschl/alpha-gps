package com.sasch.cameragps.sharednew.language

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backed by [AppCompatDelegate], which on API 33+ forwards to the platform's
 * per-app language setting; below that appcompat persists it itself. Either way
 * applying a language recreates the activity, so the flow only has to survive
 * that — this instance is process-scoped.
 */
actual class LanguagePreference actual constructor() {

    private val selectedLanguage = MutableStateFlow(readLanguage())
    actual val selected: StateFlow<AppLanguage?> = selectedLanguage.asStateFlow()

    actual fun select(language: AppLanguage?) {
        AppCompatDelegate.setApplicationLocales(
            language?.let { LocaleListCompat.forLanguageTags(it.tag) }
                ?: LocaleListCompat.getEmptyLocaleList()
        )
        selectedLanguage.value = language
    }

    actual fun refresh() {
        selectedLanguage.value = readLanguage()
    }

    private fun readLanguage(): AppLanguage? {
        // An empty list is "follow the system"; get(0) is null exactly then.
        val locale = AppCompatDelegate.getApplicationLocales()[0] ?: return null
        val tag = locale.toLanguageTag()
        // A locale the app no longer ships a translation for still has to render.
        return SupportedLanguages.fromTag(tag) ?: AppLanguage(tag, locale.displayName)
    }
}
