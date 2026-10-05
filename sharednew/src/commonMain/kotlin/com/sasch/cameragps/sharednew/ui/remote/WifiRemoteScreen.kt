package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.wifi_remote_address_failed
import cameragps.sharednew.generated.resources.wifi_remote_approval
import cameragps.sharednew.generated.resources.wifi_remote_auto_connect
import cameragps.sharednew.generated.resources.wifi_remote_auto_setup
import cameragps.sharednew.generated.resources.wifi_remote_bluetooth_required
import cameragps.sharednew.generated.resources.wifi_remote_browse
import cameragps.sharednew.generated.resources.wifi_remote_browse_unsupported
import cameragps.sharednew.generated.resources.wifi_remote_browser_back
import cameragps.sharednew.generated.resources.wifi_remote_capture
import cameragps.sharednew.generated.resources.wifi_remote_captured
import cameragps.sharednew.generated.resources.wifi_remote_close
import cameragps.sharednew.generated.resources.wifi_remote_closing
import cameragps.sharednew.generated.resources.wifi_remote_connect
import cameragps.sharednew.generated.resources.wifi_remote_connected
import cameragps.sharednew.generated.resources.wifi_remote_credentials_failed
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
import cameragps.sharednew.generated.resources.wifi_remote_prepare_only
import cameragps.sharednew.generated.resources.wifi_remote_prepare_only_hint
import cameragps.sharednew.generated.resources.wifi_remote_preparing
import cameragps.sharednew.generated.resources.wifi_remote_rejected
import cameragps.sharednew.generated.resources.wifi_remote_selection_clear
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
import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.remote.wifi.SonyImageTransfer
import com.sasch.cameragps.sharednew.remote.wifi.WifiCaptureStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiPhotoDownload
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteController
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteFailure
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemotePhase
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import com.sasch.cameragps.sharednew.remote.wifi.WifiShutdownStatus
import com.sasch.cameragps.sharednew.remote.wifi.groupCameraPhotos
import org.jetbrains.compose.resources.stringResource

class WifiRemoteViewModel(val identifier: String, val controller: WifiRemoteController) : ViewModel() {
    var host by mutableStateOf("")
    var fullScreenPreview by mutableStateOf(false)
        private set

    var selectedPhotoIds by mutableStateOf(emptySet<String>())
        private set

    var viewedPhotoIndex by mutableStateOf<Int?>(null)
        private set

    fun openPhoto(captureId: String) {
        val state = controller.sessions.value[identifier.uppercase()]?.wifiRemote ?: return
        if (!state.photoBrowser.open || selectedPhotoIds.isNotEmpty()) return
        val index = groupCameraPhotos(state.photoBrowser.photos).indexOfFirst { it.id == captureId }
        if (index >= 0) {
            controller.clearPhotoTransferResult(identifier)
            viewedPhotoIndex = state.photoBrowser.offset + index
            viewPhotoAt(viewedPhotoIndex!!)
        }
    }

    fun closePhoto() {
        controller.clearPhotoTransferResult(identifier)
        viewedPhotoIndex = null
        controller.clearPhotoPreview(identifier)
    }

    fun viewPhotoAt(index: Int) {
        if (viewedPhotoIndex == null) return
        val state = controller.sessions.value[identifier.uppercase()]?.wifiRemote ?: return
        val browser = state.photoBrowser
        if (!browser.open) return
        val captures = groupCameraPhotos(browser.photos)
        val count = captures.size
        if (index in browser.offset until browser.offset + count) {
            if (viewedPhotoIndex != index) controller.clearPhotoTransferResult(identifier)
            viewedPhotoIndex = index
            controller.showPhotoPreview(identifier, captures[index - browser.offset].preview.handle)
        } else if (!browser.loading && !state.imageTransfer.busy) {
            val offset = when {
                index == browser.offset - 1 && browser.offset > 0 -> maxOf(
                    0,
                    browser.offset - SonyImageTransfer.PAGE_SIZE
                )

                index == browser.offset + count && browser.hasMore -> browser.offset + SonyImageTransfer.PAGE_SIZE
                else -> return
            }
            controller.clearPhotoTransferResult(identifier)
            viewedPhotoIndex = index
            controller.browsePhotos(identifier, offset, preferredPhotoIndex = index)
        }
    }

    fun togglePhotoSelection(captureId: String) {
        val state = controller.sessions.value[identifier.uppercase()]?.wifiRemote ?: return
        if (!state.photoBrowser.open || state.photoBrowser.loading || state.imageTransfer.busy) return
        if (groupCameraPhotos(state.photoBrowser.photos).none { it.id == captureId }) return
        selectedPhotoIds = if (captureId in selectedPhotoIds) selectedPhotoIds - captureId
        else selectedPhotoIds + captureId
    }

    fun selectAllPhotos() {
        val state = controller.sessions.value[identifier.uppercase()]?.wifiRemote ?: return
        if (!state.photoBrowser.open || state.photoBrowser.loading || state.imageTransfer.busy) return
        selectedPhotoIds = groupCameraPhotos(state.photoBrowser.photos).map { it.id }.toSet()
    }

    fun clearPhotoSelection() {
        selectedPhotoIds = emptySet()
    }

    fun reconcilePhotoSelection() {
        val state = controller.sessions.value[identifier.uppercase()]?.wifiRemote
        if (state?.phase != WifiRemotePhase.Ready || !state.photoBrowser.open) {
            clearPhotoSelection()
            closePhoto()
        } else if (!state.photoBrowser.loading) {
            val visibleIds = groupCameraPhotos(state.photoBrowser.photos).map { it.id }.toSet()
            selectedPhotoIds = selectedPhotoIds.intersect(visibleIds)
        }
    }

    fun expandPreview() {
        val state = controller.sessions.value[identifier.uppercase()]?.wifiRemote ?: return
        if (state.phase == WifiRemotePhase.Ready && state.hasLiveView && !state.photoBrowser.open) {
            fullScreenPreview = true
        }
    }

    fun collapsePreview() { fullScreenPreview = false }

    fun navigateBack(onClose: () -> Unit) {
        when {
            fullScreenPreview -> collapsePreview()
            viewedPhotoIndex != null -> closePhoto()
            selectedPhotoIds.isNotEmpty() -> clearPhotoSelection()
            controller.sessions.value[identifier.uppercase()]?.wifiRemote?.photoBrowser?.open == true ->
                controller.leavePhotoBrowser(identifier)
            else -> onClose()
        }
    }

    override fun onCleared() {
        logging("WifiRemoteViewModel").d { "Wi-Fi remote ViewModel cleared -> Wi-Fi disconnect requested" }
        controller.disconnect(identifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiRemoteScreen(viewModel: WifiRemoteViewModel, onConnect: (String) -> Unit,
                     onWifiSettings: () -> Unit,
                     onClose: () -> Unit,
                     isExperimentalNoticeAcknowledged: () -> Boolean,
                     onAcknowledgeExperimentalNotice: () -> Unit,
                     onConnectAutomatically: () -> Unit = {},
                     wifiSettingsLabel: String = stringResource(Res.string.wifi_remote_settings),
                     networkPermissionMessage: String = stringResource(Res.string.wifi_remote_permission),
                     openingSessionMessage: String = stringResource(Res.string.wifi_remote_opening),
                     onDownloadPhotos: (List<WifiPhotoDownload>) -> Unit = { downloads ->
                         viewModel.controller.downloadPhotos(viewModel.identifier, downloads)
                     }
) {
    var noticeAcknowledged by remember { mutableStateOf(isExperimentalNoticeAcknowledged()) }
    if (!noticeAcknowledged) {
        WifiRemoteExperimentalNotice(
            onContinue = {
                onAcknowledgeExperimentalNotice()
                noticeAcknowledged = true
            },
            onCancel = onClose,
        )
    }
    val controller = viewModel.controller
    val sessions by controller.sessions.collectAsState()
    val owner by controller.owner.collectAsState()
    val image by controller.image.collectAsState()
    val id = viewModel.identifier.uppercase()
    val state = sessions[id]?.wifiRemote ?: WifiRemoteState()
    val ready = state.phase == WifiRemotePhase.Ready
    val busy = state.phase !in setOf(WifiRemotePhase.Idle, WifiRemotePhase.Failed)
    val otherCamera = owner != null && owner != id
    var manual by remember { mutableStateOf(!controller.supportsAutomaticConnection && !controller.supportsManualSetup) }
    val back = { viewModel.navigateBack(onClose) }
    val browserStateHolder = rememberSaveableStateHolder()
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = back,
    )
    LaunchedEffect(ready, state.photoBrowser.open) {
        if (!ready || state.photoBrowser.open) viewModel.collapsePreview()
    }
    LaunchedEffect(
        ready,
        state.photoBrowser.open,
        state.photoBrowser.photos,
        state.photoBrowser.loading
    ) {
        viewModel.reconcilePhotoSelection()
    }
    if (viewModel.fullScreenPreview && ready && !state.photoBrowser.open) {
        WifiFullScreenPreview(image, state, onCollapse = viewModel::collapsePreview,
            onCapture = { controller.capture(id) })
        return
    }
    if (viewModel.viewedPhotoIndex != null && ready && state.photoBrowser.open) {
        WifiPhotoViewer(state, viewModel, onDownloadPhotos)
        return
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(Res.string.wifi_remote_title)) }, navigationIcon = {
            TextButton(onClick = back,
                enabled = viewModel.selectedPhotoIds.isNotEmpty() || !state.photoBrowser.open ||
                        (!state.photoBrowser.loading && !state.imageTransfer.busy)
            ) {
                Text(
                    stringResource(
                        if (viewModel.selectedPhotoIds.isNotEmpty()) Res.string.wifi_remote_selection_clear
                        else if (state.photoBrowser.open) Res.string.wifi_remote_browser_back
                    else Res.string.wifi_remote_close))
            }
        })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (state.photoBrowser.open) {
                browserStateHolder.SaveableStateProvider("photos") {
                    WifiPhotoBrowser(state, viewModel, onDownloadPhotos)
                }
                return@BoxWithConstraints
            }
            val preview: @Composable (Modifier) -> Unit = { modifier ->
                WifiLiveViewPreview(
                    image = image.takeIf { owner == id }, state = state,
                    modifier = modifier, onExpand = viewModel::expandPreview,
                    placeholder = if (!busy && (controller.supportsAutomaticConnection || controller.supportsManualSetup)) {
                        {
                            Button(onClick = onConnectAutomatically, enabled = !otherCamera) {
                                Text(
                                    stringResource(
                                        if (controller.supportsAutomaticConnection) Res.string.wifi_remote_auto_connect
                                        else Res.string.wifi_remote_prepare_only
                                    )
                                )
                            }
                        }
                    } else null,
                )
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!busy) {
                        if (controller.supportsAutomaticConnection || controller.supportsManualSetup) {
                            Text(
                                stringResource(
                                    if (controller.supportsAutomaticConnection) Res.string.wifi_remote_auto_setup
                                    else Res.string.wifi_remote_prepare_only_hint
                                )
                            )
                            TextButton(onClick = { manual = !manual }) {
                                Text(stringResource(Res.string.wifi_remote_manual))
                            }
                        }
                        if (manual) {
                            Text(stringResource(Res.string.wifi_remote_setup))
                            OutlinedButton(onClick = onWifiSettings) { Text(wifiSettingsLabel) }
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
                    if (state.phase == WifiRemotePhase.OpeningSession) Text(openingSessionMessage)
                    if (state.phase == WifiRemotePhase.PreparingCamera) Text(stringResource(Res.string.wifi_remote_preparing))
                    state.manualNetwork?.let { credentials ->
                        WifiManualJoin(
                            credentials, onWifiSettings,
                            onContinue = { controller.continueManualConnection(id) })
                    }
                    if (state.phase == WifiRemotePhase.AwaitingNetworkApproval) Text(stringResource(Res.string.wifi_remote_approval))
                    if (state.phase == WifiRemotePhase.JoiningNetwork) Text(stringResource(Res.string.wifi_remote_joining))
                    if (state.phase == WifiRemotePhase.Closing) Text(stringResource(Res.string.wifi_remote_closing))
                    state.failure?.let { failure ->
                        Text(if (failure == WifiRemoteFailure.NetworkPermissionDenied) networkPermissionMessage else stringResource(when (failure) {
                            WifiRemoteFailure.InvalidAddress -> Res.string.wifi_remote_invalid_ip
                            WifiRemoteFailure.JoinCameraWifi -> Res.string.wifi_remote_join_wifi
                            WifiRemoteFailure.NetworkPermissionDenied -> Res.string.wifi_remote_permission
                            WifiRemoteFailure.NetworkLost -> Res.string.wifi_remote_lost
                            WifiRemoteFailure.BluetoothRequired -> Res.string.wifi_remote_bluetooth_required
                            WifiRemoteFailure.CameraSetupFailed -> Res.string.wifi_remote_setup_failed
                            WifiRemoteFailure.CameraCredentialsUnavailable -> Res.string.wifi_remote_credentials_failed
                            WifiRemoteFailure.NetworkJoinFailed -> Res.string.wifi_remote_join_failed
                            WifiRemoteFailure.CameraAddressUnavailable -> Res.string.wifi_remote_address_failed
                            WifiRemoteFailure.WifiDisabled -> Res.string.wifi_remote_wifi_disabled
                            WifiRemoteFailure.LocationServicesRequired -> Res.string.wifi_remote_location_required
                            else -> Res.string.wifi_remote_failed
                        }), color = MaterialTheme.colorScheme.error)
                    }
                    if (ready) {
                        Text(stringResource(Res.string.wifi_remote_connected, state.cameraName))
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
