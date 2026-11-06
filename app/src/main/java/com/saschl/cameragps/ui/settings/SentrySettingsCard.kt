package com.saschl.cameragps.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.sasch.cameragps.sharednew.ui.settings.SharedSentrySettingsCard
import com.saschl.cameragps.utils.CrashReporting
import com.saschl.cameragps.utils.PreferencesManager

/**
 * Android host for the shared crash-reporting settings card. The card itself
 * (switch and description) lives in `:sharednew` so iOS shows the same entry.
 */
@Composable
internal fun SentrySettingsCard() {
    val context = LocalContext.current
    var isSentryEnabled by remember {
        mutableStateOf(PreferencesManager.sentryEnabled(context))
    }

    SharedSentrySettingsCard(
        enabled = isSentryEnabled,
        onEnabledChange = { enabled ->
            isSentryEnabled = enabled
            PreferencesManager.setSentryEnabled(context, enabled)
            PreferencesManager.setSentryConsentDialogDismissed(context, true)
            CrashReporting.updateFromPreferences(context)
        },
    )
}
