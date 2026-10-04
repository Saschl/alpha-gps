package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.wifi_remote_batch_download
import cameragps.sharednew.generated.resources.wifi_remote_batch_file_count
import cameragps.sharednew.generated.resources.wifi_remote_batch_formats
import cameragps.sharednew.generated.resources.wifi_remote_batch_formats_hint
import cameragps.sharednew.generated.resources.wifi_remote_batch_nothing_to_download
import cameragps.sharednew.generated.resources.wifi_remote_jpeg_copy
import cameragps.sharednew.generated.resources.wifi_remote_jpeg_copy_hint
import cameragps.sharednew.generated.resources.wifi_remote_photo_close
import cameragps.sharednew.generated.resources.wifi_remote_photo_download_hint
import cameragps.sharednew.generated.resources.wifi_remote_selection_count
import com.sasch.cameragps.sharednew.remote.wifi.WifiCameraCapture
import com.sasch.cameragps.sharednew.remote.wifi.WifiPhotoBatchFormat
import com.sasch.cameragps.sharednew.remote.wifi.WifiPhotoDownload
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import com.sasch.cameragps.sharednew.remote.wifi.photoBatchDownloads
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoBatchDownloadSheet(
    captures: List<WifiCameraCapture>,
    state: WifiRemoteState,
    onDismiss: () -> Unit,
    onDownload: (List<WifiPhotoDownload>) -> Unit,
) {
    val options = WifiPhotoBatchFormat.entries.associateWith { format ->
        photoBatchDownloads(
            captures,
            setOf(format),
            state.photoBrowser.savedDownloads,
            state.canConvertHeif
        )
    }.filterValues { it.isNotEmpty() }
    var formats by remember {
        mutableStateOf(options.keys.singleOrNull()?.let { setOf(it) }.orEmpty())
    }
    val downloads = photoBatchDownloads(
        captures,
        formats,
        state.photoBrowser.savedDownloads,
        state.canConvertHeif
    )
    val busy = state.photoBrowser.loading || state.imageTransfer.busy
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(Res.string.wifi_remote_selection_count, captures.size),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                stringResource(Res.string.wifi_remote_batch_formats),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(Res.string.wifi_remote_batch_formats_hint),
                style = MaterialTheme.typography.bodySmall
            )
            options.forEach { (format, files) ->
                val checked = format in formats
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        checked, enabled = !busy, role = Role.Checkbox,
                        onValueChange = {
                            formats = if (it) formats + format else formats - format
                        }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Checkbox(checked, onCheckedChange = null, enabled = !busy)
                    Column {
                        Text(
                            when (format) {
                                WifiPhotoBatchFormat.Jpeg -> "JPEG"
                                WifiPhotoBatchFormat.Raw -> "RAW"
                                WifiPhotoBatchFormat.Heif -> "HEIF"
                                WifiPhotoBatchFormat.JpegCopy -> stringResource(Res.string.wifi_remote_jpeg_copy)
                            }
                        )
                        Text(
                            stringResource(Res.string.wifi_remote_batch_file_count, files.size),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (WifiPhotoBatchFormat.JpegCopy in formats) {
                Text(
                    stringResource(Res.string.wifi_remote_jpeg_copy_hint),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (options.isEmpty()) Text(stringResource(Res.string.wifi_remote_batch_nothing_to_download))
            Button(
                onClick = { onDownload(downloads) }, enabled = downloads.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(Res.string.wifi_remote_batch_download, downloads.size))
            }
            Text(
                stringResource(Res.string.wifi_remote_photo_download_hint),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.wifi_remote_photo_close))
            }
        }
    }
}
