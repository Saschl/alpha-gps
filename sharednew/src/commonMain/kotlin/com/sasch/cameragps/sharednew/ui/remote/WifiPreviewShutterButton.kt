package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private class PreviewShutterHold {
    private var release: (() -> Unit)? = null
    val active: Boolean get() = release != null
    fun start(start: () -> Boolean, stop: () -> Unit): Boolean {
        if (active || !start()) return false
        release = stop
        return true
    }

    fun stop() {
        val action = release
        release = null
        action?.invoke()
    }
}

@Composable
fun WifiPreviewShutterButton(
    enabled: Boolean,
    canHold: Boolean,
    shooting: Boolean,
    exposing: Boolean,
    label: String,
    holdLabel: String,
    onCapture: () -> Unit,
    onHoldStart: () -> Boolean,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hold = remember { PreviewShutterHold() }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCanHold by rememberUpdatedState(canHold)
    val capture by rememberUpdatedState(onCapture)
    val start by rememberUpdatedState(onHoldStart)
    val stop by rememberUpdatedState(onHoldEnd)
    DisposableEffect(hold) { onDispose { hold.stop() } }
    val click = {
        when {
            hold.active -> {
                hold.stop(); true
            }

            currentEnabled -> {
                capture(); true
            }

            else -> false
        }
    }
    Box(
        modifier.size(80.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .border(
                3.dp,
                Color.White.copy(alpha = if (enabled || shooting) 1f else 0.4f),
                CircleShape
            )
            // Busy/countdown updates must not cancel the finger that owns this hold.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!currentEnabled) return@awaitEachGesture
                    down.consume()
                    var longPress = false
                    val up = try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        longPress = true
                        null
                    }
                    if (!longPress) {
                        if (up != null) {
                            up.consume()
                            if (currentEnabled) capture()
                        }
                    } else if (currentEnabled && currentCanHold && hold.start(start, stop)) {
                        try {
                            waitForUpOrCancellation()?.consume()
                        } finally {
                            hold.stop()
                        }
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = label
                if (!enabled && !hold.active) disabled()
                onClick(action = click)
                if (canHold && enabled) onLongClick(label = holdLabel) { hold.start(start, stop) }
            }
            .onKeyEvent {
                if (it.key == Key.Enter || it.key == Key.Spacebar) {
                    if (it.type == KeyEventType.KeyUp) click()
                    true
                } else false
            }
            .focusable(enabled || hold.active),
        contentAlignment = Alignment.Center,
    ) {
        if (shooting && !exposing) CircularProgressIndicator(
            Modifier.size(48.dp),
            color = Color.White
        )
        else Box(
            Modifier.size(if (exposing) 48.dp else 64.dp).background(
                if (exposing) Color.Red else Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                CircleShape
            )
        )
    }
}
