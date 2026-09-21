package com.sasch.cameragps.sharednew.language

import kotlinx.coroutines.flow.StateFlow

/**
 * The app's language override, held in the platform's own per-app language store
 * (`AppCompatDelegate` on Android, the `AppleLanguages` user default on iOS) so the
 * choice stays in sync with the system's per-app language settings screen.
 *
 * `null` means "follow the system language".
 */
expect class LanguagePreference() {
    val selected: StateFlow<AppLanguage?>

    fun select(language: AppLanguage?)

    /** Re-read the platform store, which the user can change in the system settings. */
    fun refresh()
}

/**
 * The app-wide instance. There is no DI framework here (see CLAUDE.md), and both the
 * shared settings card and the iOS app shell — which re-keys the whole tree on a
 * language change — have to observe the same one.
 */
val appLanguagePreference: LanguagePreference by lazy { LanguagePreference() }
