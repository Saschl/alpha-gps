package com.sasch.cameragps.sharednew.language

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

/**
 * Backed by the native `AppleLanguages` preference, which is also what iOS's own
 * per-app language screen writes.
 */
actual class LanguagePreference internal constructor(
    private val defaults: NSUserDefaults,
    private val domainName: String,
) {

    actual constructor() : this(
        NSUserDefaults.standardUserDefaults,
        NSBundle.mainBundle.bundleIdentifier.orEmpty(),
    )

    private val selectedLanguage = MutableStateFlow(readLanguage())
    actual val selected: StateFlow<AppLanguage?> = selectedLanguage.asStateFlow()

    actual fun select(language: AppLanguage?) {
        if (language == null) {
            defaults.removeObjectForKey(LANGUAGE_KEY)
        } else {
            require(language in SupportedLanguages.entries)
            defaults.setObject(listOf(language.tag), forKey = LANGUAGE_KEY)
        }
        selectedLanguage.value = language
    }

    /** Reconcile a language chosen in iOS Settings while the app was inactive. */
    actual fun refresh() {
        selectedLanguage.value = readLanguage()
    }

    private fun readLanguage(): AppLanguage? {
        // Both objectForKey and stringArrayForKey search the global domain, where
        // AppleLanguages holds the system's preferred languages — that would make
        // "System" look like an explicit choice. Only this app's own domain, which
        // is what iOS's per-app language screen and select() below write to, counts.
        val languages = defaults.persistentDomainForName(domainName)?.get(LANGUAGE_KEY) as? List<*>
        val tag = languages?.firstOrNull() as? String ?: return null
        return SupportedLanguages.fromTag(tag)
    }

    private companion object {
        const val LANGUAGE_KEY = "AppleLanguages"
    }
}
