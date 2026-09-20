package com.sasch.cameragps.sharednew.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.cancel_button
import cameragps.sharednew.generated.resources.keyboard_arrow_right_24px
import cameragps.sharednew.generated.resources.language_selection
import cameragps.sharednew.generated.resources.language_system
import com.sasch.cameragps.sharednew.language.AppLanguage
import com.sasch.cameragps.sharednew.language.LanguagePreference
import com.sasch.cameragps.sharednew.language.SupportedLanguages
import com.sasch.cameragps.sharednew.language.appLanguagePreference
import com.sasch.cameragps.sharednew.ui.components.ScrollbarLazyColumn
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Language picker for the settings screens of both platforms. Persisting and applying
 * the choice is [LanguagePreference]'s job; null means follow the system language.
 */
@Composable
fun SharedLanguageSettingsCard(preference: LanguagePreference = appLanguagePreference) {
    val currentLanguage by preference.selected.collectAsState()
    // The same setting also lives in the system's per-app language screen, and a change
    // there only recreates the activity (Android) / reactivates the app (iOS) rather
    // than telling us, so re-read the platform store whenever we come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { preference.refresh() }
    LanguageSettingsCardContent(currentLanguage, preference::select)
}

/** Split out from [SharedLanguageSettingsCard] so it can be rendered with arbitrary state. */
@Composable
internal fun LanguageSettingsCardContent(
    currentLanguage: AppLanguage?,
    onLanguageSelected: (AppLanguage?) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val systemLabel = stringResource(Res.string.language_system)

    Surface(
        modifier = Modifier.fillMaxWidth().clickable { showDialog = true },
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
    ) {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.language_selection)) },
            supportingContent = { Text(currentLanguage?.displayName ?: systemLabel) },
            trailingContent = {
                Icon(
                    painterResource(Res.drawable.keyboard_arrow_right_24px),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }

    if (showDialog) {
        fun select(language: AppLanguage?) {
            showDialog = false
            onLanguageSelected(language)
        }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(Res.string.language_selection)) },
            text = {
                ScrollbarLazyColumn(modifier = Modifier.selectableGroup()) {
                    item(key = "system") {
                        LanguageOption(systemLabel, currentLanguage == null) { select(null) }
                    }
                    items(SupportedLanguages.entries, key = { it.tag }) { language ->
                        LanguageOption(language.displayName, currentLanguage?.tag == language.tag) {
                            select(language)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(Res.string.cancel_button))
                }
            },
        )
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
