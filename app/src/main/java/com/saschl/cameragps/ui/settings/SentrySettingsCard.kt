package com.saschl.cameragps.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.enable_sentry
import cameragps.sharednew.generated.resources.enable_sentry_description
import cameragps.sharednew.generated.resources.sentry_restart_required
import cameragps.sharednew.generated.resources.sentry_settings
import com.saschl.cameragps.utils.PreferencesManager
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SentrySettingsCard() {
    val context = LocalContext.current

    SettingsCard(title = stringResource(Res.string.sentry_settings)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(Res.string.enable_sentry),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(Res.string.enable_sentry_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            var isSentryEnabled by remember {
                mutableStateOf(PreferencesManager.sentryEnabled(context))
            }

            Switch(
                checked = isSentryEnabled,
                onCheckedChange = { enabled ->
                    isSentryEnabled = enabled
                    PreferencesManager.setSentryEnabled(context, enabled)
                }
            )
        }

        // Restart hint
        Text(
            text = stringResource(Res.string.sentry_restart_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

