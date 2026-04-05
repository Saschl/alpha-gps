package com.saschl.cameragps.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.cancel_button
import cameragps.sharednew.generated.resources.language_selection
import cameragps.sharednew.generated.resources.language_system
import com.sasch.cameragps.sharednew.language.AppLanguage
import com.saschl.cameragps.R
import com.saschl.cameragps.utils.LanguageManager
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LanguageSettingsCard(
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    val systemLabel = stringResource(Res.string.language_system)
    val context = LocalContext.current
    val currentLanguage = LanguageManager.getCurrentLanguage(context)


    Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLanguageDialog = true },
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        ) {

        Column {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.language_selection)) },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.keyboard_arrow_right_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }/*{
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(Res.string.language_selection),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = currentLanguage?.displayName
                            ?: "$systemLabel (${Locale.getDefault().displayName})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }*/


    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = currentLanguage,
            onLanguageUnset = {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            },
            onLanguageSelected = { language ->
                val activity = context as? androidx.activity.ComponentActivity
                activity?.let {
                    LanguageManager.applyLanguageToActivity(it, language)
                }
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

@Composable
private fun LanguageSelectionDialog(
    currentLanguage: AppLanguage?,
    onLanguageSelected: (AppLanguage) -> Unit,
    onLanguageUnset: () -> Unit,
    onDismiss: () -> Unit
) {
    val systemLabel = stringResource(Res.string.language_system)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.language_selection),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            LazyColumn {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageUnset() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentLanguage == null,
                            onClick = onLanguageUnset
                        )
                        Text(
                            text = systemLabel,
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                items(LanguageManager.getSupportedLanguages()) { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(language) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = language.tag == currentLanguage?.tag,
                            onClick = { onLanguageSelected(language) }
                        )
                        Text(
                            text = language.displayName,
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel_button))
            }
        }
    )
}



