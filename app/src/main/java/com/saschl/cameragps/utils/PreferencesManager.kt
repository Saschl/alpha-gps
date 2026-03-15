package com.saschl.cameragps.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.saschl.cameragps.shared.ui.settings.LocationProvider
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

object PreferencesManager {
    private const val PREFS_NAME = "camera_gps_prefs"
    private const val KEY_FIRST_LAUNCH = "is_first_launch"
    private const val KEY_APP_ENABLED = "app_enabled"
    private const val KEY_BATTERY_OPTIMIZATION_DIALOG_DISMISSED = "battery_optimization_dialog_dismissed"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_REVIEW_HINT_LAST_SHOWN = "review_hint_last_shown"
    private const val KEY_REVIEW_HINT_SHOWN_TIMES = "review_hint_shown_times"
    private const val KEY_IGNORE_PERMISSIONS = "ignore_permissions"
    private const val KEY_LOCATION_PROVIDER = "location_provider"
    private const val LOCATION_PROVIDER_PLAY_SERVICES = "play_services"
    private const val LOCATION_PROVIDER_PLATFORM = "platform"

    // LocationProvider enum lives in shared module (com.saschl.cameragps.shared.ui.settings)

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isFirstLaunch(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_FIRST_LAUNCH, true)
    }

    fun setFirstLaunchCompleted(context: Context) {
        getPreferences(context).edit {
            putBoolean(KEY_FIRST_LAUNCH, false)
        }
    }
    fun showFirstLaunch(context: Context) {
        getPreferences(context).edit {
            putBoolean(KEY_FIRST_LAUNCH, true)
        }
    }

    fun isAppEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_APP_ENABLED, true)
    }

    fun setAppEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit {
            putBoolean(KEY_APP_ENABLED, enabled)
        }
    }

    fun setBatteryOptimizationDialogDismissed(context: Context, dismissed: Boolean) {
        getPreferences(context).edit {
            putBoolean(KEY_BATTERY_OPTIMIZATION_DIALOG_DISMISSED, dismissed)
        }
    }

    fun reviewHintLastShownDaysAgo(context: Context, initialize: Boolean = false): Long {
        val lastShown = getPreferences(context).getLong(KEY_REVIEW_HINT_LAST_SHOWN, 0L)

        var lastShownInstant: Instant
        if (lastShown == 0L && initialize) {
            // give the user one day breathing room before showing the hint after setting up the first device
            lastShownInstant = Instant.now().minus(29, ChronoUnit.DAYS)
            getPreferences(context).edit {
                putLong(KEY_REVIEW_HINT_LAST_SHOWN, lastShownInstant.epochSecond)
            }
        } else {
            lastShownInstant = Instant.ofEpochSecond(lastShown)

        }
        val daysAgo = Duration.between(lastShownInstant, Instant.now()).toDays()
        return daysAgo
    }

    fun setReviewHintShownNow(context: Context) {
        getPreferences(context).edit {
            putLong(KEY_REVIEW_HINT_LAST_SHOWN, Instant.now().epochSecond)
        }
    }

    fun resetReviewHintShown(context: Context) {
        getPreferences(context).edit {
            putLong(KEY_REVIEW_HINT_LAST_SHOWN, 0L)
        }
    }

    fun reviewHintShownTimes(applicationContext: Context): Int {
        return getPreferences(applicationContext).getInt(KEY_REVIEW_HINT_SHOWN_TIMES, 0)
    }

    fun increaseReviewHintShownTimes(applicationContext: Context) {
        val currentTimes = reviewHintShownTimes(applicationContext)
        getPreferences(applicationContext).edit {
            putInt(KEY_REVIEW_HINT_SHOWN_TIMES, currentTimes + 1)
        }
    }

    fun decreaseReviewHintShownTimes(applicationContext: Context) {
        val currentTimes = reviewHintShownTimes(applicationContext)
        getPreferences(applicationContext).edit {
            putInt(KEY_REVIEW_HINT_SHOWN_TIMES, currentTimes - 1)
        }
    }

    fun reviewHintLastShownInstant(context: Context): Instant {
        return Instant.ofEpochSecond(getPreferences(context).getLong(KEY_REVIEW_HINT_LAST_SHOWN, 0))
    }

    fun resetReviewHintData(context: Context) {
        getPreferences(context).edit {
            putLong(KEY_REVIEW_HINT_LAST_SHOWN, 0L)
            putInt(KEY_REVIEW_HINT_SHOWN_TIMES, 0)
        }
    }

    fun logLevel(context: Context): Int {
        return getPreferences(context).getInt(KEY_LOG_LEVEL, Log.INFO)
    }

    fun setLogLevel(context: Context, logLevel: Int) {
        getPreferences(context).edit {
            putInt(KEY_LOG_LEVEL, logLevel)
        }
    }

    fun setAutoStartAfterBootEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit {
            putBoolean("auto_start_after_boot", enabled)
        }
    }

    fun getAutoStartAfterBootEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean("auto_start_after_boot", false)
    }

    fun sentryEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean("sentry_enabled", false)
    }

    fun setSentryEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit {
            putBoolean("sentry_enabled", enabled)
        }
    }

    fun isSentryConsentDialogDismissed(context: Context): Boolean {
        return getPreferences(context).getBoolean("sentry_consent_dialog_dismissed", false)
    }

    fun setSentryConsentDialogDismissed(context: Context, dismissed: Boolean) {
        getPreferences(context).edit {
            putBoolean("sentry_consent_dialog_dismissed", dismissed)
        }
    }

    fun isPermissionsIgnored(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_IGNORE_PERMISSIONS, false)
    }

    fun setPermissionsIgnored(context: Context, ignored: Boolean) {
        getPreferences(context).edit {
            putBoolean(KEY_IGNORE_PERMISSIONS, ignored)
        }
    }

    fun getLocationProvider(context: Context): LocationProvider {
        val stored = getPreferences(context).getString(
            KEY_LOCATION_PROVIDER,
            LOCATION_PROVIDER_PLAY_SERVICES
        )
        return if (stored == LOCATION_PROVIDER_PLATFORM) {
            LocationProvider.PLATFORM
        } else {
            LocationProvider.PLAY_SERVICES
        }
    }

    fun setLocationProvider(context: Context, provider: LocationProvider) {
        val stored = if (provider == LocationProvider.PLATFORM) {
            LOCATION_PROVIDER_PLATFORM
        } else {
            LOCATION_PROVIDER_PLAY_SERVICES
        }
        getPreferences(context).edit {
            putString(KEY_LOCATION_PROVIDER, stored)
        }
    }
}
