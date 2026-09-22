package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.wifi_remote_address_failed
import cameragps.sharednew.generated.resources.wifi_remote_approval
import cameragps.sharednew.generated.resources.wifi_remote_auto_connect
import cameragps.sharednew.generated.resources.wifi_remote_auto_setup
import cameragps.sharednew.generated.resources.wifi_remote_bluetooth_required
import cameragps.sharednew.generated.resources.wifi_remote_browse
import cameragps.sharednew.generated.resources.wifi_remote_browse_unsupported
import cameragps.sharednew.generated.resources.wifi_remote_capture
import cameragps.sharednew.generated.resources.wifi_remote_capture_hint
import cameragps.sharednew.generated.resources.wifi_remote_captured
import cameragps.sharednew.generated.resources.wifi_remote_close
import cameragps.sharednew.generated.resources.wifi_remote_closing
import cameragps.sharednew.generated.resources.wifi_remote_connect
import cameragps.sharednew.generated.resources.wifi_remote_connected
import cameragps.sharednew.generated.resources.wifi_remote_disconnect
import cameragps.sharednew.generated.resources.wifi_remote_failed
import cameragps.sharednew.generated.resources.wifi_remote_invalid_ip
import cameragps.sharednew.generated.resources.wifi_remote_ip
import cameragps.sharednew.generated.resources.wifi_remote_join_failed
import cameragps.sharednew.generated.resources.wifi_remote_join_wifi
import cameragps.sharednew.generated.resources.wifi_remote_joining
import cameragps.sharednew.generated.resources.wifi_remote_location_required
import cameragps.sharednew.generated.resources.wifi_remote_lost
import cameragps.sharednew.generated.resources.wifi_remote_manual
import cameragps.sharednew.generated.resources.wifi_remote_opening
import cameragps.sharednew.generated.resources.wifi_remote_other_camera
import cameragps.sharednew.generated.resources.wifi_remote_permission
import cameragps.sharednew.generated.resources.wifi_remote_preparing
import cameragps.sharednew.generated.resources.wifi_remote_preview_failed
import cameragps.sharednew.generated.resources.wifi_remote_preview_waiting
import cameragps.sharednew.generated.resources.wifi_remote_rejected
import cameragps.sharednew.generated.resources.wifi_remote_settings
import cameragps.sharednew.generated.resources.wifi_remote_setup
import cameragps.sharednew.generated.resources.wifi_remote_setup_failed
import cameragps.sharednew.generated.resources.wifi_remote_shooting
import cameragps.sharednew.generated.resources.wifi_remote_shutdown_requested
import cameragps.sharednew.generated.resources.wifi_remote_shutdown_unavailable
import cameragps.sharednew.generated.resources.wifi_remote_shutdown_unconfirmed
import cameragps.sharednew.generated.resources.wifi_remote_title
import cameragps.sharednew.generated.resources.wifi_remote_uncertain
import cameragps.sharednew.generated.resources.wifi_remote_wifi_disabled
import com.sasch.cameragps.sharednew.remote.wifi.WifiCaptureStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiPreviewStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteController
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteFailure
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemotePhase
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import com.sasch.cameragps.sharednew.remote.wifi.WifiShutdownStatus
import org.jetbrains.compose.resources.stringResource

class WifiRemoteViewModel(val identifier: String, val controller: WifiRemoteController) : ViewModel() {
    var host by mutableStateOf("")
    override fun onCleared() { controller.disconnect(identifier) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiRemoteScreen(viewModel: WifiRemoteViewModel, onConnect: (String) -> Unit,
                     onWifiSettings: () -> Unit,
                     onClose: () -> Unit,
                     onConnectAutomatically: () -> Unit = {},
                     onDownloadPhoto: (Long) -> Unit = {
                         viewModel.controller.downloadPhoto(
                             viewModel.identifier,
                             it
                         )
                     }
) {
    val controller = viewModel.controller
    val sessions by controller.sessions.collectAsState()
    val owner by controller.owner.collectAsState()
    val image by controller.image.collectAsState()
    val id = viewModel.identifier.uppercase()
    val state = sessions[id]?.wifiRemote ?: WifiRemoteState()
    val ready = state.phase == WifiRemotePhase.Ready
    val busy = state.phase !in setOf(WifiRemotePhase.Idle, WifiRemotePhase.Failed)
    val otherCamera = owner != null && owner != id
    var manual by remember { mutableStateOf(!controller.supportsAutomaticConnection) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(Res.string.wifi_remote_title)) }, navigationIcon = {
            TextButton(onClick = onClose) { Text(stringResource(Res.string.wifi_remote_close)) }
        })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (state.photoBrowser.open) {
                WifiPhotoBrowser(state, controller, id, onDownloadPhoto)
                return@BoxWithConstraints
            }
            val preview: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                    if (owner == id && image != null) {
                        Image(image!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else {
                        Text(stringResource(if (state.preview == WifiPreviewStatus.Failed)
                            Res.string.wifi_remote_preview_failed else Res.string.wifi_remote_preview_waiting),
                            color = Color.White, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!busy) {
                        if (controller.supportsAutomaticConnection) {
                            Text(stringResource(Res.string.wifi_remote_auto_setup))
                            Button(onClick = onConnectAutomatically, enabled = !otherCamera) {
                                Text(stringResource(Res.string.wifi_remote_auto_connect))
                            }
                            TextButton(onClick = { manual = !manual }) {
                                Text(stringResource(Res.string.wifi_remote_manual))
                            }
                        }
                        if (manual) {
                            Text(stringResource(Res.string.wifi_remote_setup))
                            OutlinedButton(onClick = onWifiSettings) { Text(stringResource(Res.string.wifi_remote_settings)) }
                            OutlinedTextField(value = viewModel.host, onValueChange = { viewModel.host = it.take(64) },
                                label = { Text(stringResource(Res.string.wifi_remote_ip)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            Button(onClick = { onConnect(viewModel.host) }, enabled = viewModel.host.isNotBlank() && !otherCamera) {
                                Text(stringResource(Res.string.wifi_remote_connect))
                            }
                        }
                    }
                    if (otherCamera) Text(stringResource(Res.string.wifi_remote_other_camera))
                    if (state.phase == WifiRemotePhase.Idle) {
                        val shutdownMessage = when (state.wifiShutdown) {
                            WifiShutdownStatus.Requested -> Res.string.wifi_remote_shutdown_requested
                            WifiShutdownStatus.Unavailable -> Res.string.wifi_remote_shutdown_unavailable
                            WifiShutdownStatus.Unconfirmed -> Res.string.wifi_remote_shutdown_unconfirmed
                            WifiShutdownStatus.NotRequested -> null
                        }
                        shutdownMessage?.let { Text(stringResource(it)) }
                    }
                    if (state.phase == WifiRemotePhase.OpeningSession) Text(stringResource(Res.string.wifi_remote_opening))
                    if (state.phase == WifiRemotePhase.PreparingCamera) Text(stringResource(Res.string.wifi_remote_preparing))
                    if (state.phase == WifiRemotePhase.AwaitingNetworkApproval) Text(stringResource(Res.string.wifi_remote_approval))
                    if (state.phase == WifiRemotePhase.JoiningNetwork) Text(stringResource(Res.string.wifi_remote_joining))
                    if (state.phase == WifiRemotePhase.Closing) Text(stringResource(Res.string.wifi_remote_closing))
                    state.failure?.let { failure ->
                        Text(stringResource(when (failure) {
                            WifiRemoteFailure.InvalidAddress -> Res.string.wifi_remote_invalid_ip
                            WifiRemoteFailure.JoinCameraWifi -> Res.string.wifi_remote_join_wifi
                            WifiRemoteFailure.NetworkPermissionDenied -> Res.string.wifi_remote_permission
                            WifiRemoteFailure.NetworkLost -> Res.string.wifi_remote_lost
                            WifiRemoteFailure.BluetoothRequired -> Res.string.wifi_remote_bluetooth_required
                            WifiRemoteFailure.CameraSetupFailed -> Res.string.wifi_remote_setup_failed
                            WifiRemoteFailure.NetworkJoinFailed -> Res.string.wifi_remote_join_failed
                            WifiRemoteFailure.CameraAddressUnavailable -> Res.string.wifi_remote_address_failed
                            WifiRemoteFailure.WifiDisabled -> Res.string.wifi_remote_wifi_disabled
                            WifiRemoteFailure.LocationServicesRequired -> Res.string.wifi_remote_location_required
                            else -> Res.string.wifi_remote_failed
                        }), color = MaterialTheme.colorScheme.error)
                    }
                    if (ready) {
                        Text(stringResource(Res.string.wifi_remote_connected, state.cameraName))
                        Text(stringResource(Res.string.wifi_remote_capture_hint))
                        if (state.canTransferImages) OutlinedButton(
                            onClick = { controller.browsePhotos(id) },
                            enabled = state.capture != WifiCaptureStatus.Shooting
                        ) {
                            Text(stringResource(Res.string.wifi_remote_browse))
                        } else Text(stringResource(Res.string.wifi_remote_browse_unsupported))
                        Button(onClick = { controller.capture(id) },
                            enabled = state.canCaptureStill && state.capture != WifiCaptureStatus.Shooting && !state.imageTransfer.busy
                        ) {
                            Text(stringResource(if (state.capture == WifiCaptureStatus.Shooting)
                                Res.string.wifi_remote_shooting else Res.string.wifi_remote_capture))
                        }
                        val result = when (state.capture) {
                            WifiCaptureStatus.Captured -> Res.string.wifi_remote_captured
                            WifiCaptureStatus.Uncertain -> Res.string.wifi_remote_uncertain
                            WifiCaptureStatus.Rejected -> Res.string.wifi_remote_rejected
                            else -> null
                        }
                        result?.let { Text(stringResource(it)) }
                    }
                    if (busy) OutlinedButton(onClick = { controller.disconnect(id) }, enabled = state.phase != WifiRemotePhase.Closing) {
                        Text(stringResource(Res.string.wifi_remote_disconnect))
                    }
                }
            }
            if (maxWidth > maxHeight) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                preview(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.widthIn(max = 300.dp).fillMaxHeight())
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                preview(Modifier.fillMaxWidth().weight(1f))
                controls(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
