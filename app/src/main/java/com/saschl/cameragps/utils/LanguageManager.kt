package com.saschl.cameragps.utils

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.saschl.cameragps.shared.language.AppLanguage
import com.saschl.cameragps.shared.language.SupportedLanguages
import java.util.Locale

object LanguageManager {

    fun getSupportedLanguages(): List<AppLanguage> = SupportedLanguages.entries


    /**
     * Apply language immediately to the current activity
     */
    fun applyLanguageToActivity(activity: Activity, language: AppLanguage?) {
        if (language == null) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            return
        }
        val locale = Locale.forLanguageTag(language.tag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(locale))
    }

    /**
     * Get the currently selected language
     */
    fun getCurrentLanguage(context: Context): AppLanguage? {
        val locale = if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            null
        } else {
            AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
        }
        locale ?: return null

        return SupportedLanguages.fromTag(locale.toLanguageTag())
            ?: AppLanguage(tag = locale.toLanguageTag(), displayName = locale.displayName)
    }
}
