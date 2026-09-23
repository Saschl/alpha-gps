package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.*
import com.sasch.cameragps.sharednew.remote.wifi.SonyImageTransfer
import com.sasch.cameragps.sharednew.remote.wifi.WifiCameraCapture
import com.sasch.cameragps.sharednew.remote.wifi.WifiImageTransferState
import com.sasch.cameragps.sharednew.remote.wifi.WifiImageTransferStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteController
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import com.sasch.cameragps.sharednew.remote.wifi.formatLabel
import com.sasch.cameragps.sharednew.remote.wifi.groupCameraPhotos
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WifiPhotoBrowser(
    state: WifiRemoteState, controller: WifiRemoteController,
    identifier: String, onDownload: (Long) -> Unit
) {
    val browser = state.photoBrowser
    val thumbnails by controller.thumbnails.collectAsState()
    val captures = remember(browser.photos) { groupCameraPhotos(browser.photos) }
    val transfer = state.imageTransfer
    val busy = browser.loading || transfer.busy
    var selectedId by rememberSaveable(identifier, browser.offset) { mutableStateOf<String?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp), modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.wifi_remote_browser_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.wifi_remote_browser_tap_hint), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { controller.leavePhotoBrowser(identifier) }, enabled = !busy) {
                        Text(stringResource(Res.string.wifi_remote_browser_back))
                    }
                    TextButton(onClick = { controller.browsePhotos(identifier) }, enabled = !busy) {
                        Text(stringResource(Res.string.wifi_remote_browser_refresh))
                    }
                }
                if (browser.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (browser.failed) Text(
                    stringResource(Res.string.wifi_remote_browser_failed), color = MaterialTheme.colorScheme.error
                )
                PhotoTransferStatus(transfer)
                if (busy) TextButton(onClick = { controller.cancelPhotoOperation(identifier) }) {
                    Text(stringResource(Res.string.wifi_remote_transfer_cancel))
                }
                if (!browser.loading && captures.isEmpty() && !browser.failed) {
                    Text(stringResource(Res.string.wifi_remote_browser_empty))
                }
            }
        }
        items(captures, key = { it.id }) { capture ->
            Card(onClick = { selectedId = capture.id }, modifier = Modifier.semantics {
                contentDescription = capture.name
            }) {
                Box {
                    PhotoPreview(thumbnails[capture.preview.handle], browser.loading)
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            capture.files.joinToString(" + ") { it.formatLabel }, color = Color.White,
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp)
                        )
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = { controller.browsePhotos(identifier, maxOf(0, browser.offset - SonyImageTransfer.PAGE_SIZE)) },
                    enabled = !busy && browser.offset > 0
                ) { Text(stringResource(Res.string.wifi_remote_browser_previous)) }
                if (browser.totalObjects > 0) Text(stringResource(
                    Res.string.wifi_remote_browser_page, browser.offset / SonyImageTransfer.PAGE_SIZE + 1
                ))
                OutlinedButton(
                    onClick = { controller.browsePhotos(identifier, browser.offset + SonyImageTransfer.PAGE_SIZE) },
                    enabled = !busy && browser.hasMore
                ) { Text(stringResource(Res.string.wifi_remote_browser_next)) }
            }
        }
    }
    captures.firstOrNull { it.id == selectedId }?.let { capture ->
        PhotoDetailsSheet(
            capture, thumbnails[capture.preview.handle], state,
            onDismiss = { selectedId = null }, onDownload = onDownload,
            onCancel = { controller.cancelPhotoOperation(identifier) }
        )
    }
}

@Composable
private fun PhotoPreview(image: ImageBitmap?, loading: Boolean) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 2f).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else if (loading) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(Res.string.wifi_remote_preview_unavailable),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoDetailsSheet(
    capture: WifiCameraCapture, image: ImageBitmap?, state: WifiRemoteState,
    onDismiss: () -> Unit, onDownload: (Long) -> Unit, onCancel: () -> Unit,
) {
    val browser = state.photoBrowser
    val transfer = state.imageTransfer
    var selectedHandle by rememberSaveable(capture.id) {
        mutableStateOf(capture.files.singleOrNull()?.handle)
    }
    val selected = capture.files.firstOrNull { it.handle == selectedHandle }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(capture.name, style = MaterialTheme.typography.headlineSmall)
            PhotoPreview(image, browser.loading)
            if (capture.preview.capturedAt.isNotBlank()) {
                Text(stringResource(Res.string.wifi_remote_photo_captured, capture.preview.capturedAt.replace('T', ' ')))
            }
            Text(stringResource(Res.string.wifi_remote_photo_format), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                capture.files.forEach { file ->
                    val saved = file.handle in browser.savedHandles
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = file.handle == selectedHandle,
                            enabled = !transfer.busy && file.downloadable && !saved,
                            role = Role.RadioButton, onClick = { selectedHandle = file.handle }
                        ).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(selected = file.handle == selectedHandle, onClick = null,
                            enabled = !transfer.busy && file.downloadable && !saved)
                        Column(Modifier.weight(1f)) {
                            Text(file.formatLabel, style = MaterialTheme.typography.titleSmall)
                            Text(file.filename, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(Res.string.wifi_remote_photo_size, (file.size + 1023) / 1024),
                                style = MaterialTheme.typography.bodySmall)
                            if (saved) Text(stringResource(Res.string.wifi_remote_download_saved))
                            if (!file.downloadable) Text(stringResource(Res.string.wifi_remote_download_too_large))
                        }
                    }
                }
            }
            PhotoTransferStatus(transfer)
            if (transfer.busy) TextButton(onClick = onCancel) {
                Text(stringResource(Res.string.wifi_remote_transfer_cancel))
            }
            Button(
                onClick = { selected?.let { onDownload(it.handle) } }, modifier = Modifier.fillMaxWidth(),
                enabled = !browser.loading && !transfer.busy && selected != null && selected.downloadable &&
                    selected.handle !in browser.savedHandles
            ) {
                Text(if (selected == null) stringResource(Res.string.wifi_remote_download)
                    else stringResource(Res.string.wifi_remote_download_format, selected.formatLabel))
            }
            Text(stringResource(Res.string.wifi_remote_photo_download_hint), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.wifi_remote_photo_close))
            }
        }
    }
}

@Composable
private fun PhotoTransferStatus(transfer: WifiImageTransferState) {
    when (transfer.status) {
        WifiImageTransferStatus.Downloading -> {
            Text(stringResource(Res.string.wifi_remote_transfer_progress, transfer.filename))
            LinearProgressIndicator(progress = {
                if (transfer.totalBytes > 0) transfer.bytesReceived.toFloat() / transfer.totalBytes else 0f
            }, modifier = Modifier.fillMaxWidth())
        }
        WifiImageTransferStatus.Saved -> Text(stringResource(Res.string.wifi_remote_transfer_saved, transfer.filename))
        WifiImageTransferStatus.Failed -> Text(stringResource(Res.string.wifi_remote_transfer_failed))
        WifiImageTransferStatus.Cancelled -> Text(stringResource(Res.string.wifi_remote_transfer_cancelled))
        WifiImageTransferStatus.PermissionDenied -> Text(stringResource(Res.string.wifi_remote_storage_permission))
        WifiImageTransferStatus.Idle -> Unit
    }
}
