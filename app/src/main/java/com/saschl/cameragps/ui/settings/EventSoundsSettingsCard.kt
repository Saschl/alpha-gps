package com.saschl.cameragps.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.cancel_button
import cameragps.sharednew.generated.resources.event_sounds_camera_connected
import cameragps.sharednew.generated.resources.event_sounds_camera_disconnected
import cameragps.sharednew.generated.resources.event_sounds_choose_sound
import cameragps.sharednew.generated.resources.event_sounds_configure_disconnect_channel
import cameragps.sharednew.generated.resources.event_sounds_configure_transmission_channel
import cameragps.sharednew.generated.resources.event_sounds_current_custom
import cameragps.sharednew.generated.resources.event_sounds_current_default
import cameragps.sharednew.generated.resources.event_sounds_current_silent
import cameragps.sharednew.generated.resources.event_sounds_description
import cameragps.sharednew.generated.resources.event_sounds_enable_all
import cameragps.sharednew.generated.resources.event_sounds_location_acquired
import cameragps.sharednew.generated.resources.event_sounds_location_invalid
import cameragps.sharednew.generated.resources.event_sounds_notification_channels_title
import cameragps.sharednew.generated.resources.event_sounds_set_default
import cameragps.sharednew.generated.resources.event_sounds_set_silent
import cameragps.sharednew.generated.resources.event_sounds_title
import com.saschl.cameragps.R
import com.saschl.cameragps.notification.NotificationsHelper
import com.saschl.cameragps.service.TransmissionSoundEvent
import com.saschl.cameragps.service.TransmissionSoundMode
import com.saschl.cameragps.utils.PreferencesManager
import org.jetbrains.compose.resources.stringResource

@Composable
@Preview
internal fun EventSoundsSettingsCard() {
    val context = LocalContext.current

    val eventTitles = mapOf(
        TransmissionSoundEvent.LOCATION_ACQUIRED to stringResource(Res.string.event_sounds_location_acquired),
        TransmissionSoundEvent.LOCATION_INVALID to stringResource(Res.string.event_sounds_location_invalid),
        TransmissionSoundEvent.CAMERA_CONNECTED to stringResource(Res.string.event_sounds_camera_connected),
        TransmissionSoundEvent.CAMERA_DISCONNECTED to stringResource(Res.string.event_sounds_camera_disconnected)
    )

    var pendingPickerEvent by remember { mutableStateOf<TransmissionSoundEvent?>(null) }
    var selectedEventForModeChange by remember { mutableStateOf<TransmissionSoundEvent?>(null) }

    var enabled by remember {
        mutableStateOf(PreferencesManager.isTransmissionEventSoundsEnabled(context))
    }

    val eventEnabledStates = remember {
        mutableStateMapOf<TransmissionSoundEvent, Boolean>().apply {
            eventTitles.keys.forEach { event ->
                this[event] = PreferencesManager.isTransmissionEventEnabled(context, event)
            }
        }
    }

    val eventModeStates = remember {
        mutableStateMapOf<TransmissionSoundEvent, TransmissionSoundMode>().apply {
            eventTitles.keys.forEach { event ->
                this[event] = PreferencesManager.getTransmissionEventSoundMode(context, event)
            }
        }
    }

    val soundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val event = pendingPickerEvent
        pendingPickerEvent = null

        if (event == null || result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult

        val pickedUri = result.data.getPickedRingtoneUri()
        if (pickedUri == null) {
            eventModeStates[event] = TransmissionSoundMode.SILENT
            PreferencesManager.setTransmissionEventSoundMode(
                context,
                event,
                TransmissionSoundMode.SILENT
            )
            PreferencesManager.setTransmissionEventSoundUri(context, event, null)
        } else {
            eventModeStates[event] = TransmissionSoundMode.CUSTOM
            PreferencesManager.setTransmissionEventSoundMode(
                context,
                event,
                TransmissionSoundMode.CUSTOM
            )
            PreferencesManager.setTransmissionEventSoundUri(context, event, pickedUri.toString())
        }
    }

    Column {
        Text(
            text = stringResource(Res.string.event_sounds_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        SettingsListGroup {
            SettingsToggleRow(
                title = stringResource(Res.string.event_sounds_enable_all),
                checked = enabled,
                onCheckedChange = { value ->
                    enabled = value
                    PreferencesManager.setTransmissionEventSoundsEnabled(context, value)
                }
            )
        }

        Text(
            text = stringResource(Res.string.event_sounds_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        SettingsListGroup {
            eventTitles.forEach { (event, title) ->
                EventSettingsRow(
                    title = title,
                    subtitle = getModeLabel(
                        eventModeStates[event] ?: TransmissionSoundMode.DEFAULT
                    ),
                    checked = eventEnabledStates[event] == true,
                    controlsEnabled = enabled,
                    onCheckedChange = { value ->
                        eventEnabledStates[event] = value
                        PreferencesManager.setTransmissionEventEnabled(context, event, value)
                    },
                    onClick = { selectedEventForModeChange = event }
                )
            }
        }

        Text(
            text = stringResource(Res.string.event_sounds_notification_channels_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        SettingsListGroup {
            SettingsActionRow(
                title = stringResource(Res.string.event_sounds_configure_transmission_channel),
                onClick = {
                    openNotificationChannelSettings(
                        context,
                        NotificationsHelper.NOTIFICATION_CHANNEL_ID
                    )
                }
            )
            SettingsActionRow(
                title = stringResource(Res.string.event_sounds_configure_disconnect_channel),
                onClick = {
                    openNotificationChannelSettings(
                        context,
                        NotificationsHelper.DISCONNECT_NOTIFICATION_CHANNEL
                    )
                }
            )
        }
    }

    selectedEventForModeChange?.let { event ->
        SoundModeSelectionDialog(
            title = eventTitles[event].orEmpty(),
            currentMode = eventModeStates[event] ?: TransmissionSoundMode.DEFAULT,
            onDismiss = { selectedEventForModeChange = null },
            onSelectDefault = {
                eventModeStates[event] = TransmissionSoundMode.DEFAULT
                PreferencesManager.setTransmissionEventSoundMode(
                    context,
                    event,
                    TransmissionSoundMode.DEFAULT
                )
                selectedEventForModeChange = null
            },
            onSelectSilent = {
                eventModeStates[event] = TransmissionSoundMode.SILENT
                PreferencesManager.setTransmissionEventSoundMode(
                    context,
                    event,
                    TransmissionSoundMode.SILENT
                )
                PreferencesManager.setTransmissionEventSoundUri(context, event, null)
                selectedEventForModeChange = null
            },
            onSelectCustom = {
                pendingPickerEvent = event
                selectedEventForModeChange = null
                soundPickerLauncher.launch(
                    createRingtonePickerIntent(
                        existingUri = PreferencesManager.getTransmissionEventSoundUri(
                            context,
                            event
                        )
                            ?.let(Uri::parse)
                    )
                )
            }
        )
    }
}

@Composable
private fun SettingsListGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun EventSettingsRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    controlsEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = controlsEnabled,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = controlsEnabled && checked, onClick = onClick)
    )

    if (controlsEnabled && checked) {
        SettingsActionRow(
            title = stringResource(Res.string.event_sounds_choose_sound),
            onClick = onClick
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.keyboard_arrow_right_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        overlineContent = null,
        supportingContent = null,
        leadingContent = null
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SoundModeSelectionDialog(
    title: String,
    currentMode: TransmissionSoundMode,
    onDismiss: () -> Unit,
    onSelectDefault: () -> Unit,
    onSelectSilent: () -> Unit,
    onSelectCustom: () -> Unit,
) {
    val options = listOf(
        TransmissionSoundMode.DEFAULT to stringResource(Res.string.event_sounds_set_default),
        TransmissionSoundMode.SILENT to stringResource(Res.string.event_sounds_set_silent),
        TransmissionSoundMode.CUSTOM to stringResource(Res.string.event_sounds_choose_sound)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { (mode, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == currentMode,

                            onClick = {
                                when (mode) {
                                    TransmissionSoundMode.DEFAULT -> onSelectDefault()
                                    TransmissionSoundMode.SILENT -> onSelectSilent()
                                    TransmissionSoundMode.CUSTOM -> onSelectCustom()
                                }
                            }
                        )
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(Res.string.cancel_button))
            }
        }
    )
}

@Composable
private fun getModeLabel(mode: TransmissionSoundMode): String {
    return when (mode) {
        TransmissionSoundMode.DEFAULT -> stringResource(Res.string.event_sounds_current_default)
        TransmissionSoundMode.SILENT -> stringResource(Res.string.event_sounds_current_silent)
        TransmissionSoundMode.CUSTOM -> stringResource(Res.string.event_sounds_current_custom)
    }
}

private fun createRingtonePickerIntent(existingUri: Uri?): Intent {
    return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
    }
}

private fun openNotificationChannelSettings(context: Context, channelId: String) {
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    runCatching {
        context.startActivity(intent)
    }.onFailure {
        val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallback)
    }
}

@Suppress("DEPRECATION")
private fun Intent?.getPickedRingtoneUri(): Uri? {
    val safeIntent = this ?: return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        safeIntent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    } else {
        safeIntent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }
}
