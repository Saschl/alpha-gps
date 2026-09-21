package com.sasch.cameragps.sharednew.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.enable_sentry
import cameragps.sharednew.generated.resources.enable_sentry_description
import cameragps.sharednew.generated.resources.sentry_consent_allow
import cameragps.sharednew.generated.resources.sentry_consent_decline
import cameragps.sharednew.generated.resources.sentry_consent_dont_show
import cameragps.sharednew.generated.resources.sentry_consent_message
import cameragps.sharednew.generated.resources.sentry_consent_title
import cameragps.sharednew.generated.resources.sentry_restart_required
import cameragps.sharednew.generated.resources.sentry_settings
import org.jetbrains.compose.resources.stringResource

/**
 * One-time opt-in for crash reporting, shown on the first launch of a build that
 * ships a crash reporter.
 *
 * The composable is deliberately side-effect free: the caller persists the
 * answer and starts (or does not start) the SDK, because that part is
 * platform-specific — Android has to respect the foss flavor's no-op
 * `CrashReporting`, iOS starts the Kotlin Multiplatform SDK directly.
 *
 * All three buttons are terminal; the dialog is never shown again afterwards.
 */
@Composable
fun SharedSentryConsentDialog(
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    onDontShowAgain: () -> Unit,
) {
    AlertDialog(
        // Dismissing without choosing must not count as consent, so an outside
        // tap is treated exactly like "no thanks".
        onDismissRequest = onDontShowAgain,
        title = {
            Text(
                text = stringResource(Res.string.sentry_consent_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.sentry_consent_message),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(Res.string.sentry_consent_allow),
                        fontWeight = FontWeight.Medium
                    )
                }

                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(Res.string.sentry_consent_decline),
                        fontWeight = FontWeight.Medium
                    )
                }

                TextButton(
                    onClick = onDontShowAgain,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.sentry_consent_dont_show))
                }
            }
        }
    )
}

/**
 * Settings entry for crash reporting.
 *
 * Toggling only records the preference — neither platform can start or stop the
 * Sentry SDK cleanly mid-process, hence the restart hint below the switch.
 */
@Composable
fun SharedSentrySettingsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SharedSettingsCard(title = stringResource(Res.string.sentry_settings), modifier = modifier) {
        SharedToggleRow(
            title = stringResource(Res.string.enable_sentry),
            description = stringResource(Res.string.enable_sentry_description),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )

        Text(
            text = stringResource(Res.string.sentry_restart_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
