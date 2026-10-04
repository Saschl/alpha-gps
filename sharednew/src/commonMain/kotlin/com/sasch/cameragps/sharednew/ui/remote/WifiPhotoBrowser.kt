package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.wifi_remote_batch_progress
import cameragps.sharednew.generated.resources.wifi_remote_browser_back
import cameragps.sharednew.generated.resources.wifi_remote_browser_empty
import cameragps.sharednew.generated.resources.wifi_remote_browser_failed
import cameragps.sharednew.generated.resources.wifi_remote_browser_next
import cameragps.sharednew.generated.resources.wifi_remote_browser_page
import cameragps.sharednew.generated.resources.wifi_remote_browser_previous
import cameragps.sharednew.generated.resources.wifi_remote_browser_refresh
import cameragps.sharednew.generated.resources.wifi_remote_browser_tap_hint
import cameragps.sharednew.generated.resources.wifi_remote_browser_title
import cameragps.sharednew.generated.resources.wifi_remote_conversion_failed
import cameragps.sharednew.generated.resources.wifi_remote_converting_jpeg
import cameragps.sharednew.generated.resources.wifi_remote_download
import cameragps.sharednew.generated.resources.wifi_remote_download_format
import cameragps.sharednew.generated.resources.wifi_remote_download_saved
import cameragps.sharednew.generated.resources.wifi_remote_download_too_large
import cameragps.sharednew.generated.resources.wifi_remote_jpeg_copy
import cameragps.sharednew.generated.resources.wifi_remote_jpeg_copy_hint
import cameragps.sharednew.generated.resources.wifi_remote_photo_captured
import cameragps.sharednew.generated.resources.wifi_remote_photo_close
import cameragps.sharednew.generated.resources.wifi_remote_photo_download_hint
import cameragps.sharednew.generated.resources.wifi_remote_photo_format
import cameragps.sharednew.generated.resources.wifi_remote_photo_size
import cameragps.sharednew.generated.resources.wifi_remote_preview_unavailable
import cameragps.sharednew.generated.resources.wifi_remote_selection_all
import cameragps.sharednew.generated.resources.wifi_remote_selection_count
import cameragps.sharednew.generated.resources.wifi_remote_selection_start
import cameragps.sharednew.generated.resources.wifi_remote_storage_permission
import cameragps.sharednew.generated.resources.wifi_remote_transfer_cancel
import cameragps.sharednew.generated.resources.wifi_remote_transfer_cancelled
import cameragps.sharednew.generated.resources.wifi_remote_transfer_failed
import cameragps.sharednew.generated.resources.wifi_remote_transfer_progress
import cameragps.sharednew.generated.resources.wifi_remote_transfer_saved
import com.sasch.cameragps.sharednew.remote.wifi.SonyImageTransfer
import com.sasch.cameragps.sharednew.remote.wifi.WifiCameraCapture
import com.sasch.cameragps.sharednew.remote.wifi.WifiImageTransferState
import com.sasch.cameragps.sharednew.remote.wifi.WifiImageTransferStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiPhotoDownload
import com.sasch.cameragps.sharednew.remote.wifi.WifiPhotoDownloadFormat
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import com.sasch.cameragps.sharednew.remote.wifi.formatLabel
import com.sasch.cameragps.sharednew.remote.wifi.groupCameraPhotos
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WifiPhotoBrowser(
    state: WifiRemoteState, viewModel: WifiRemoteViewModel,
    onDownload: (List<WifiPhotoDownload>) -> Unit
) {
    val controller = viewModel.controller
    val identifier = viewModel.identifier
    val browser = state.photoBrowser
    val thumbnails by controller.thumbnails.collectAsState()
    val captures = remember(browser.photos) { groupCameraPhotos(browser.photos) }
    val transfer = state.imageTransfer
    val busy = browser.loading || transfer.busy
    val selectedIds = viewModel.selectedPhotoIds
    val selecting = selectedIds.isNotEmpty()
    val gridState = rememberLazyGridState()
    var gridOffset by rememberSaveable { mutableStateOf(browser.offset) }
    LaunchedEffect(browser.offset) {
        if (gridOffset != browser.offset) {
            gridState.scrollToItem(0)
            gridOffset = browser.offset
        }
    }
    var showBatchOptions by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selecting) {
        if (!selecting) showBatchOptions = false
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selecting) {
            Text(
                stringResource(Res.string.wifi_remote_selection_count, selectedIds.size),
                style = MaterialTheme.typography.titleLarge
            )
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = viewModel::selectAllPhotos,
                    enabled = !busy && selectedIds.size < captures.size
                ) {
                    Text(stringResource(Res.string.wifi_remote_selection_all))
                }
                Button(onClick = { showBatchOptions = true }, enabled = !busy) {
                    Text(stringResource(Res.string.wifi_remote_download))
                }
            }
        }
        PhotoTransferStatus(transfer)
        if (busy) TextButton(onClick = { controller.cancelPhotoOperation(identifier) }) {
            Text(stringResource(Res.string.wifi_remote_transfer_cancel))
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(148.dp), modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!selecting) {
                        Text(
                            stringResource(Res.string.wifi_remote_browser_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            stringResource(Res.string.wifi_remote_browser_tap_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(
                                onClick = { controller.leavePhotoBrowser(identifier) },
                                enabled = !busy
                            ) {
                                Text(stringResource(Res.string.wifi_remote_browser_back))
                            }
                            TextButton(
                                onClick = { controller.browsePhotos(identifier) },
                                enabled = !busy
                            ) {
                                Text(stringResource(Res.string.wifi_remote_browser_refresh))
                            }
                        }
                    }
                    if (browser.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (browser.failed) Text(
                        stringResource(Res.string.wifi_remote_browser_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                    if (!browser.loading && captures.isEmpty() && !browser.failed) {
                        Text(stringResource(Res.string.wifi_remote_browser_empty))
                    }
                }
            }
            items(captures, key = { it.id }) { capture ->
                val isSelected = capture.id in selectedIds
                val selectLabel = stringResource(Res.string.wifi_remote_selection_start)
                Card(
                    border = if (isSelected) BorderStroke(
                        3.dp,
                        MaterialTheme.colorScheme.primary
                    ) else null,
                    modifier = Modifier.combinedClickable(
                        enabled = !selecting || !busy,
                        role = if (selecting) Role.Checkbox else Role.Button,
                        onClick = {
                            if (selecting) viewModel.togglePhotoSelection(capture.id) else viewModel.openPhoto(
                                capture.id
                            )
                        },
                        onLongClickLabel = selectLabel,
                        onLongClick = {
                            if (!busy) {
                                viewModel.togglePhotoSelection(capture.id)
                            }
                        },
                    ).semantics {
                        contentDescription = capture.name
                        if (selecting) selected = isSelected
                    },
                ) {
                    Box {
                        PhotoPreview(thumbnails[capture.preview.handle], browser.loading)
                        if (selecting) Surface(
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = Color.Black.copy(alpha = 0.65f),
                        ) {
                            Checkbox(
                                checked = isSelected, onCheckedChange = null, enabled = !busy,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Surface(
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                capture.files.joinToString(" + ") { it.formatLabel },
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = {
                            controller.browsePhotos(
                                identifier,
                                maxOf(0, browser.offset - SonyImageTransfer.PAGE_SIZE)
                            )
                        },
                        enabled = !busy && !selecting && browser.offset > 0
                    ) { Text(stringResource(Res.string.wifi_remote_browser_previous)) }
                    if (browser.totalObjects > 0) Text(
                        stringResource(
                            Res.string.wifi_remote_browser_page,
                            browser.offset / SonyImageTransfer.PAGE_SIZE + 1
                        )
                    )
                    OutlinedButton(
                        onClick = {
                            controller.browsePhotos(
                                identifier,
                                browser.offset + SonyImageTransfer.PAGE_SIZE
                            )
                        },
                        enabled = !busy && !selecting && browser.hasMore
                    ) { Text(stringResource(Res.string.wifi_remote_browser_next)) }
                }
            }
        }
    }
    if (showBatchOptions && selecting) {
        PhotoBatchDownloadSheet(
            captures.filter { it.id in selectedIds }, state,
            onDismiss = { showBatchOptions = false },
            onDownload = { downloads -> showBatchOptions = false; onDownload(downloads) },
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
internal fun PhotoDetailsSheet(
    capture: WifiCameraCapture, image: ImageBitmap?, state: WifiRemoteState,
    onDismiss: () -> Unit, onDownload: (Long, WifiPhotoDownloadFormat) -> Unit, onCancel: () -> Unit,
) {
    val browser = state.photoBrowser
    val transfer = state.imageTransfer
    var selectedHandle by rememberSaveable(capture.id) {
        mutableStateOf(capture.files.singleOrNull()?.handle)
    }
    var selectedFormat by rememberSaveable(capture.id) { mutableStateOf(WifiPhotoDownloadFormat.Original) }
    val selected = capture.files.firstOrNull { it.handle == selectedHandle }
    val selectedDownload = selected?.let { WifiPhotoDownload(it.handle, selectedFormat) }
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
                    val formats = if (state.canConvertHeif && file.mimeType == "image/heif")
                        WifiPhotoDownloadFormat.entries else listOf(WifiPhotoDownloadFormat.Original)
                    formats.forEach { format ->
                        val converted = format == WifiPhotoDownloadFormat.Jpeg
                        val saved = WifiPhotoDownload(file.handle, format) in browser.savedDownloads
                        val isSelected = file.handle == selectedHandle && format == selectedFormat
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = isSelected,
                                enabled = !transfer.busy && file.downloadable && !saved,
                                role = Role.RadioButton, onClick = { selectedHandle = file.handle; selectedFormat = format }
                            ).padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(selected = isSelected, onClick = null,
                                enabled = !transfer.busy && file.downloadable && !saved)
                            Column(Modifier.weight(1f)) {
                                Text(if (converted) stringResource(Res.string.wifi_remote_jpeg_copy) else file.formatLabel,
                                    style = MaterialTheme.typography.titleSmall)
                                Text(if (converted) file.filename.substringBeforeLast('.') + ".jpg" else file.filename,
                                    style = MaterialTheme.typography.bodySmall)
                                if (converted) Text(stringResource(Res.string.wifi_remote_jpeg_copy_hint),
                                    style = MaterialTheme.typography.bodySmall)
                                else Text(stringResource(Res.string.wifi_remote_photo_size, (file.size + 1023) / 1024),
                                    style = MaterialTheme.typography.bodySmall)
                                if (saved) Text(stringResource(Res.string.wifi_remote_download_saved))
                                if (!file.downloadable) Text(stringResource(Res.string.wifi_remote_download_too_large))
                            }
                        }
                    }
                }
            }
            PhotoTransferStatus(transfer)
            if (transfer.busy) TextButton(onClick = onCancel) {
                Text(stringResource(Res.string.wifi_remote_transfer_cancel))
            }
            Button(
                onClick = { selected?.let { onDownload(it.handle, selectedFormat) } }, modifier = Modifier.fillMaxWidth(),
                enabled = !browser.loading && !transfer.busy && selected != null && selected.downloadable &&
                    selectedDownload !in browser.savedDownloads
            ) {
                Text(if (selected == null) stringResource(Res.string.wifi_remote_download)
                    else stringResource(Res.string.wifi_remote_download_format,
                        if (selectedFormat == WifiPhotoDownloadFormat.Jpeg) "JPEG" else selected.formatLabel))
            }
            Text(stringResource(Res.string.wifi_remote_photo_download_hint), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.wifi_remote_photo_close))
            }
        }
    }
}

@Composable
internal fun PhotoTransferStatus(transfer: WifiImageTransferState) {
    if (transfer.totalFiles > 1) {
        Text(
            stringResource(
                Res.string.wifi_remote_batch_progress,
                transfer.completedFiles,
                transfer.totalFiles
            )
        )
    }
    when (transfer.status) {
        WifiImageTransferStatus.Downloading -> {
            Text(stringResource(Res.string.wifi_remote_transfer_progress, transfer.filename))
            LinearProgressIndicator(progress = {
                if (transfer.totalBytes > 0) transfer.bytesReceived.toFloat() / transfer.totalBytes else 0f
            }, modifier = Modifier.fillMaxWidth())
        }
        WifiImageTransferStatus.Converting -> {
            Text(stringResource(Res.string.wifi_remote_converting_jpeg))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        WifiImageTransferStatus.ConversionFailed -> Text(stringResource(Res.string.wifi_remote_conversion_failed))
        WifiImageTransferStatus.Saved -> Text(stringResource(Res.string.wifi_remote_transfer_saved, transfer.filename))
        WifiImageTransferStatus.Failed -> Text(stringResource(Res.string.wifi_remote_transfer_failed))
        WifiImageTransferStatus.Cancelled -> Text(stringResource(Res.string.wifi_remote_transfer_cancelled))
        WifiImageTransferStatus.PermissionDenied -> Text(stringResource(Res.string.wifi_remote_storage_permission))
        WifiImageTransferStatus.Idle -> Unit
    }
}
