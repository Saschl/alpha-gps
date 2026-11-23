package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.*
import com.sasch.cameragps.sharednew.remote.BulbPhase
import com.sasch.cameragps.sharednew.remote.BulbTimerState
import com.sasch.cameragps.sharednew.remote.wifi.WifiCaptureStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import org.jetbrains.compose.resources.stringResource

@Composable
fun WifiCaptureControls(
    identifier: String,
    state: WifiRemoteState,
    bulb: BulbTimerState,
    onCapture: () -> Unit,
    onStartTimer: (Int) -> Boolean,
    onStopTimer: () -> Unit,
    onBrowsePhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showTimer by rememberSaveable(identifier) { mutableStateOf(false) }
    var duration by rememberSaveable(identifier) { mutableStateOf("60") }
    val seconds = duration.toIntOrNull()?.takeIf { it in 1..3600 }
    val available =
        !bulb.active && state.capture != WifiCaptureStatus.Shooting && !state.imageTransfer.busy
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            if (bulb.active) BulbTimerStatus(bulb)
            else when (state.capture) {
                WifiCaptureStatus.Captured -> Text(stringResource(Res.string.wifi_remote_captured))
                WifiCaptureStatus.Uncertain -> Text(stringResource(Res.string.wifi_remote_uncertain))
                WifiCaptureStatus.Rejected -> Text(stringResource(Res.string.wifi_remote_rejected))
                WifiCaptureStatus.Idle -> if (bulb.phase != BulbPhase.Idle) BulbTimerStatus(bulb)
                WifiCaptureStatus.Shooting -> Unit
            }
        }
        Button(
            onClick = if (bulb.active) onStopTimer else onCapture,
            enabled = if (bulb.active) bulb.canStop else available && state.canCaptureStill,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (bulb.active) Res.string.bulb_stop
                    else if (state.capture == WifiCaptureStatus.Shooting) Res.string.wifi_remote_shooting
                    else Res.string.wifi_remote_capture
                )
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.canTransferImages) OutlinedButton(
                onClick = onBrowsePhotos, enabled = available, modifier = Modifier.weight(1f),
            ) { Text(stringResource(Res.string.wifi_remote_photos)) }
            if (state.canCaptureBulb) OutlinedButton(
                onClick = { showTimer = true }, enabled = available, modifier = Modifier.weight(1f),
            ) { Text(stringResource(Res.string.bulb_title)) }
        }
    }
    if (showTimer && !bulb.active) AlertDialog(
        onDismissRequest = { showTimer = false },
        title = { Text(stringResource(Res.string.bulb_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = duration,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all(Char::isDigit)) duration = value
                    },
                    label = { Text(stringResource(Res.string.bulb_duration)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = seconds == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(Res.string.bulb_setup),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { seconds?.let { if (onStartTimer(it)) showTimer = false } },
                enabled = available && state.canCaptureBulb && seconds != null,
            ) { Text(stringResource(Res.string.bulb_start)) }
        },
        dismissButton = {
            TextButton(onClick = { showTimer = false }) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
