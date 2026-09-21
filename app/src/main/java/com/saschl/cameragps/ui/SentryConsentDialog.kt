package com.saschl.cameragps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.sasch.cameragps.sharednew.ui.settings.SharedSentryConsentDialog
import com.saschl.cameragps.utils.CrashReporting
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber

/**
 * Android host for the shared consent dialog: the layout and copy live in
 * `:sharednew` so iOS asks the exact same question; only persisting the answer
 * and starting Sentry are Android-specific (and a no-op in the foss flavor).
 */
@Composable
fun SentryConsentDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    SharedSentryConsentDialog(
        onAllow = {
            PreferencesManager.setSentryEnabled(context, true)
            PreferencesManager.setSentryConsentDialogDismissed(context, true)
            CrashReporting.init(context)
            Timber.i("User accepted Sentry error reporting, initializing Sentry")
            onDismiss()
        },
        onDecline = {
            PreferencesManager.setSentryEnabled(context, false)
            PreferencesManager.setSentryConsentDialogDismissed(context, true)
            Timber.i("User declined Sentry error reporting")
            onDismiss()
        },
        onDontShowAgain = {
            PreferencesManager.setSentryEnabled(context, false)
            PreferencesManager.setSentryConsentDialogDismissed(context, true)
            Timber.i("User chose not to show Sentry consent dialog again")
            onDismiss()
        },
    )
}
