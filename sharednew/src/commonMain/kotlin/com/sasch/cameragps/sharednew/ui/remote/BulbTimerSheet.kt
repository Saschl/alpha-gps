package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cameragps.sharednew.generated.resources.*
import com.sasch.cameragps.sharednew.remote.BulbPhase
import com.sasch.cameragps.sharednew.remote.BulbTimerState
import com.sasch.cameragps.sharednew.remote.bulbDurationSeconds
import org.jetbrains.compose.resources.stringResource

@Composable
internal expect fun KeepBulbScreenAwake()

@Composable
internal fun BulbTimerLifecycle(
    identifier: String,
    state: BulbTimerState,
    onStop: () -> Unit,
    keepAwake: Boolean = true
) {
    val stop by rememberUpdatedState(onStop)
    if (state.active) {
        if (keepAwake) KeepBulbScreenAwake()
        DisposableEffect(identifier) { onDispose { stop() } }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stop() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulbTimerSheet(
    cameraName: String,
    initialSeconds: Int,
    enabled: Boolean,
    onStart: (Int) -> Boolean,
    onDismiss: () -> Unit,
) {
    var duration by rememberSaveable { mutableStateOf(initialSeconds.toString()) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    val seconds = duration.toIntOrNull()?.takeIf { it in bulbDurationSeconds }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.bulb_title), style = MaterialTheme.typography.titleLarge)
            Text(cameraName, style = MaterialTheme.typography.titleMedium)
            BulbDurationField(duration) { duration = it }
            Text(stringResource(Res.string.bulb_setup), style = MaterialTheme.typography.bodySmall)
            if (rejected) Text(stringResource(Res.string.bulb_failed), color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    if (seconds != null) {
                        if (onStart(seconds)) onDismiss() else rejected = true
                    }
                },
                enabled = enabled && seconds != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.bulb_start)) }
        }
    }
}

@Composable
internal fun BulbDurationField(duration: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = duration,
        onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(stringResource(Res.string.bulb_duration)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        isError = duration.toIntOrNull() !in bulbDurationSeconds,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ActiveBulbTimer(bulb: BulbTimerState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (bulb.phase == BulbPhase.Exposing) {
                val seconds = bulb.remainingSeconds
                val clock = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                Text(stringResource(Res.string.bulb_time_remaining, clock))
            } else BulbTimerStatus(bulb)
        }
        if (bulb.canStop) FilledTonalButton(onClick = onStop) {
            Text(stringResource(Res.string.bulb_stop))
        }
    }
}

@Composable
internal fun BulbTimerStatus(state: BulbTimerState) {
    val message = when (state.phase) {
        BulbPhase.Idle -> null
        BulbPhase.Starting -> Res.string.bulb_starting
        BulbPhase.Exposing -> Res.string.bulb_remaining
        BulbPhase.Stopping -> Res.string.bulb_stopping
        BulbPhase.Processing -> Res.string.bulb_processing
        BulbPhase.Finished -> Res.string.bulb_finished
        BulbPhase.Uncertain -> Res.string.bulb_uncertain
        BulbPhase.Failed -> Res.string.bulb_failed
    }
    if (message != null) Text(
        if (state.phase == BulbPhase.Exposing)
            stringResource(message, state.remainingSeconds) else stringResource(message)
    )
}
