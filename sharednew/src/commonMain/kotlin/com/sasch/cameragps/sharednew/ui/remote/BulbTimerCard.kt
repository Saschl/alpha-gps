package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cameragps.sharednew.generated.resources.*
import com.sasch.cameragps.sharednew.remote.BulbPhase
import com.sasch.cameragps.sharednew.remote.BulbTimerState
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

@Composable
fun BulbTimerCard(
    identifier: String,
    state: BulbTimerState,
    enabled: Boolean,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
) {
    var duration by rememberSaveable(identifier) { mutableStateOf("60") }
    val seconds = duration.toIntOrNull()?.takeIf { it in 1..3600 }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.bulb_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(stringResource(Res.string.bulb_setup), style = MaterialTheme.typography.bodySmall)
            if (!state.active) {
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
            }
            BulbTimerStatus(state)
            Button(
                onClick = { if (state.active) onStop() else seconds?.let(onStart) },
                enabled = if (state.active) state.canStop else enabled && seconds != null,
            ) {
                Text(stringResource(if (state.active) Res.string.bulb_stop else Res.string.bulb_start))
            }
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
