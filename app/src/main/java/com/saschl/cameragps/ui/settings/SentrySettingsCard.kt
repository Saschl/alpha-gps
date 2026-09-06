package com.saschl.cameragps.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.sasch.cameragps.sharednew.ui.settings.SharedSentrySettingsCard
import com.saschl.cameragps.utils.PreferencesManager

/**
 * Android host for the shared crash-reporting settings card. The card itself
 * (switch, description, restart hint) lives in `:sharednew` so iOS shows the
 * same entry; only the preference storage is Android-specific.
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
        },
    )
}
