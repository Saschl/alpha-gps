package com.saschl.cameragps.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.cancel_button
import cameragps.sharednew.generated.resources.log_level
import cameragps.sharednew.generated.resources.log_settings
import com.saschl.cameragps.service.FileTree
import com.saschl.cameragps.utils.PreferencesManager
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

@Composable
internal fun LogLevelSettingsCard() {
    val context = LocalContext.current
    val currentLogLevel = remember {
        mutableIntStateOf(PreferencesManager.logLevel(context))
    }
    var showLogLevelDialog by remember { mutableStateOf(false) }

    SettingsCard(title = stringResource(Res.string.log_settings)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLogLevelDialog = true },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.log_level),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = getLogLevelName(currentLogLevel.intValue),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (showLogLevelDialog) {
            LogLevelSelectionDialog(
                currentLevel = currentLogLevel.intValue,
                onLevelSelected = { level ->
                    currentLogLevel.intValue = level
                    PreferencesManager.setLogLevel(context, level)
                    showLogLevelDialog = false
                    Timber.uprootAll()
                    Timber.plant(FileTree(context, level))
                },
                onDismiss = { showLogLevelDialog = false }
            )
        }
    }
}

private fun getLogLevelName(level: Int): String {
    return when (level) {
        android.util.Log.VERBOSE -> "VERBOSE"
        android.util.Log.DEBUG -> "DEBUG"
        android.util.Log.INFO -> "INFO"
        android.util.Log.WARN -> "WARN"
        android.util.Log.ERROR -> "ERROR"
        else -> "DEBUG"
    }
}

@Composable
private fun LogLevelSelectionDialog(
    currentLevel: Int,
    onLevelSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val logLevels = listOf(
        android.util.Log.VERBOSE to "VERBOSE",
        android.util.Log.DEBUG to "DEBUG",
        android.util.Log.INFO to "INFO",
        android.util.Log.WARN to "WARN",
        android.util.Log.ERROR to "ERROR"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.log_level),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            LazyColumn {
                items(logLevels) { (level, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLevelSelected(level) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = level == currentLevel,
                            onClick = { onLevelSelected(level) }
                        )
                        Text(
                            text = name,
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

