package com.sasch.cameragps.sharednew.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.whats_new_close
import cameragps.sharednew.generated.resources.whats_new_open
import cameragps.sharednew.generated.resources.whats_new_title
import cameragps.sharednew.generated.resources.whats_new_version
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsCard
import org.jetbrains.compose.resources.stringResource

@Composable
fun WhatsNewDialog(release: ReleaseNotes, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.whats_new_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(Res.string.whats_new_version, release.version),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                release.highlights.forEach { highlight ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("•")
                        Text(stringResource(highlight), modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.whats_new_close)) }
        },
    )
}

@Composable
fun WhatsNewSettingsCard(state: WhatsNewState) {
    val release = state.release ?: return
    var showDialog by rememberSaveable { mutableStateOf(false) }
    SharedSettingsCard(title = stringResource(Res.string.whats_new_title)) {
        Text(stringResource(Res.string.whats_new_version, release.version))
        TextButton(onClick = { showDialog = true }) {
            Text(stringResource(Res.string.whats_new_open))
        }
    }
    if (showDialog) {
        WhatsNewDialog(release) {
            state.dismiss()
            showDialog = false
        }
    }
}
