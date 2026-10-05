package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.fullscreen_24px
import cameragps.sharednew.generated.resources.fullscreen_exit_24px
import cameragps.sharednew.generated.resources.wifi_remote_capture
import cameragps.sharednew.generated.resources.wifi_remote_captured
import cameragps.sharednew.generated.resources.wifi_remote_fullscreen
import cameragps.sharednew.generated.resources.wifi_remote_minimize
import cameragps.sharednew.generated.resources.wifi_remote_preview_failed
import cameragps.sharednew.generated.resources.wifi_remote_preview_waiting
import cameragps.sharednew.generated.resources.wifi_remote_rejected
import cameragps.sharednew.generated.resources.wifi_remote_shooting
import cameragps.sharednew.generated.resources.wifi_remote_uncertain
import com.sasch.cameragps.sharednew.remote.wifi.WifiCaptureStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiPreviewStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WifiLiveViewPreview(
    image: ImageBitmap?,
    state: WifiRemoteState,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (onExpand != null) IconButton(
                onClick = onExpand,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape),
            ) {
                Icon(painterResource(Res.drawable.fullscreen_24px),
                    contentDescription = stringResource(Res.string.wifi_remote_fullscreen), tint = Color.White)
            }
        } else if (placeholder != null) {
            Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) { placeholder() }
        } else Text(
            stringResource(if (state.preview == WifiPreviewStatus.Failed)
                Res.string.wifi_remote_preview_failed else Res.string.wifi_remote_preview_waiting),
            color = Color.White, modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
internal fun WifiFullScreenPreview(
    image: ImageBitmap?,
    state: WifiRemoteState,
    onCollapse: () -> Unit,
    onCapture: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        WifiLiveViewPreview(image, state, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            IconButton(onClick = onCollapse,
                modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.6f), CircleShape)) {
                Icon(painterResource(Res.drawable.fullscreen_exit_24px),
                    contentDescription = stringResource(Res.string.wifi_remote_minimize), tint = Color.White)
            }
            val shooting = state.capture == WifiCaptureStatus.Shooting
            val captureEnabled = state.canCaptureStill && !shooting && !state.imageTransfer.busy
            val captureLabel = stringResource(if (shooting) Res.string.wifi_remote_shooting else Res.string.wifi_remote_capture)
            Column(
                modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onCapture, enabled = captureEnabled,
                    modifier = Modifier.size(80.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .border(3.dp, Color.White.copy(alpha = if (captureEnabled || shooting) 1f else 0.4f), CircleShape)
                        .semantics { contentDescription = captureLabel }) {
                    if (shooting) CircularProgressIndicator(Modifier.size(48.dp), color = Color.White)
                    else Box(Modifier.size(64.dp).background(
                        Color.White.copy(alpha = if (captureEnabled) 1f else 0.4f), CircleShape))
                }
            }
            val result = when (state.capture) {
                WifiCaptureStatus.Captured -> Res.string.wifi_remote_captured
                WifiCaptureStatus.Uncertain -> Res.string.wifi_remote_uncertain
                WifiCaptureStatus.Rejected -> Res.string.wifi_remote_rejected
                else -> null
            }
            result?.let {
                Text(stringResource(it), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(if (landscape) Alignment.BottomCenter else Alignment.TopCenter)
                        .padding(horizontal = 56.dp).background(Color.Black.copy(alpha = 0.6f))
                        .padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
