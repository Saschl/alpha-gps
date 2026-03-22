package com.sasch.cameragps.sharednew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_controls
import cameragps.sharednew.generated.resources.arrow_back_24px
import cameragps.sharednew.generated.resources.auto_scan
import cameragps.sharednew.generated.resources.auto_scan_description
import cameragps.sharednew.generated.resources.back
import cameragps.sharednew.generated.resources.enable_app
import cameragps.sharednew.generated.resources.enable_app_description
import cameragps.sharednew.generated.resources.help_menu_item
import cameragps.sharednew.generated.resources.ios_shared_components_help_text
import cameragps.sharednew.generated.resources.is_there_documenation
import cameragps.sharednew.generated.resources.is_there_documenation_answer
import cameragps.sharednew.generated.resources.reset_welcome
import cameragps.sharednew.generated.resources.settings
import cameragps.sharednew.generated.resources.show_welcome_again_description
import cameragps.sharednew.generated.resources.welcome_get_started_button
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsCard
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsScreen
import com.sasch.cameragps.sharednew.ui.settings.SharedToggleRow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun IosSettingsScreen(
    isAppEnabled: Boolean,
    autoScanEnabled: Boolean,
    onBackClick: () -> Unit,
    onAppEnabledChange: (Boolean) -> Unit,
    onAutoScanEnabledChange: (Boolean) -> Unit,
    onShowWelcomeAgain: () -> Unit,
) {
    SharedSettingsScreen(
        title = stringResource(Res.string.settings),
        onBackClick = onBackClick,
        onTitleClick = {},
        navigationIcon = {
            Icon(
                painterResource(Res.drawable.arrow_back_24px),
                contentDescription = stringResource(Res.string.back)
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                SharedSettingsCard(title = stringResource(Res.string.app_controls)) {
                    SharedToggleRow(
                        title = stringResource(Res.string.enable_app),
                        description = stringResource(Res.string.enable_app_description),
                        checked = isAppEnabled,
                        onCheckedChange = onAppEnabledChange,
                    )
                    SharedToggleRow(
                        title = stringResource(Res.string.auto_scan),
                        description = stringResource(Res.string.auto_scan_description),
                        checked = autoScanEnabled,
                        onCheckedChange = onAutoScanEnabledChange,
                    )
                    SharedToggleRow(
                        title = stringResource(Res.string.reset_welcome),
                        description = stringResource(Res.string.show_welcome_again_description),
                        checked = false,
                        onCheckedChange = { if (it) onShowWelcomeAgain() },
                        trailing = {
                            TextButton(onClick = onShowWelcomeAgain) {
                                Text(stringResource(Res.string.welcome_get_started_button))
                            }
                        },
                    )
                }
            }
            item {
                SharedSettingsCard(title = stringResource(Res.string.help_menu_item)) {
                    Text(
                        text = stringResource(Res.string.ios_shared_components_help_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SharedSettingsCard(title = stringResource(Res.string.is_there_documenation)) {
                    Text(
                        text = stringResource(Res.string.is_there_documenation_answer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


