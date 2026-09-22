package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.wifi_remote_browse
import cameragps.sharednew.generated.resources.wifi_remote_browser_back
import cameragps.sharednew.generated.resources.wifi_remote_browser_empty
import cameragps.sharednew.generated.resources.wifi_remote_browser_failed
import cameragps.sharednew.generated.resources.wifi_remote_browser_hint
import cameragps.sharednew.generated.resources.wifi_remote_browser_loading
import cameragps.sharednew.generated.resources.wifi_remote_browser_next
import cameragps.sharednew.generated.resources.wifi_remote_browser_page
import cameragps.sharednew.generated.resources.wifi_remote_browser_previous
import cameragps.sharednew.generated.resources.wifi_remote_browser_refresh
import cameragps.sharednew.generated.resources.wifi_remote_download
import cameragps.sharednew.generated.resources.wifi_remote_download_saved
import cameragps.sharednew.generated.resources.wifi_remote_download_too_large
import cameragps.sharednew.generated.resources.wifi_remote_photo_size
import cameragps.sharednew.generated.resources.wifi_remote_storage_permission
import cameragps.sharednew.generated.resources.wifi_remote_transfer_cancel
import cameragps.sharednew.generated.resources.wifi_remote_transfer_cancelled
import cameragps.sharednew.generated.resources.wifi_remote_transfer_failed
import cameragps.sharednew.generated.resources.wifi_remote_transfer_progress
import cameragps.sharednew.generated.resources.wifi_remote_transfer_saved
import com.sasch.cameragps.sharednew.remote.wifi.SonyImageTransfer
import com.sasch.cameragps.sharednew.remote.wifi.WifiImageTransferStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteController
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WifiPhotoBrowser(
    state: WifiRemoteState, controller: WifiRemoteController,
    identifier: String, onDownload: (Long) -> Unit
) {
    val browser = state.photoBrowser
    val thumbnails by controller.thumbnails.collectAsState()
    val transfer = state.imageTransfer
    val busy = browser.loading || transfer.busy
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                stringResource(Res.string.wifi_remote_browse),
                style = MaterialTheme.typography.titleLarge
            )
            Text(stringResource(Res.string.wifi_remote_browser_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { controller.leavePhotoBrowser(identifier) },
                    enabled = !busy
                ) {
                    Text(stringResource(Res.string.wifi_remote_browser_back))
                }
                TextButton(onClick = { controller.browsePhotos(identifier) }, enabled = !busy) {
                    Text(stringResource(Res.string.wifi_remote_browser_refresh))
                }
            }
            if (browser.loading) Text(stringResource(Res.string.wifi_remote_browser_loading))
            if (browser.failed) Text(
                stringResource(Res.string.wifi_remote_browser_failed),
                color = MaterialTheme.colorScheme.error
            )
            when (transfer.status) {
                WifiImageTransferStatus.Downloading -> {
                    Text(
                        stringResource(
                            Res.string.wifi_remote_transfer_progress,
                            transfer.filename
                        )
                    )
                    LinearProgressIndicator(progress = {
                        if (transfer.totalBytes > 0) transfer.bytesReceived.toFloat() / transfer.totalBytes else 0f
                    }, modifier = Modifier.fillMaxWidth())
                }

                WifiImageTransferStatus.Saved -> Text(
                    stringResource(
                        Res.string.wifi_remote_transfer_saved,
                        transfer.filename
                    )
                )

                WifiImageTransferStatus.Failed -> Text(stringResource(Res.string.wifi_remote_transfer_failed))
                WifiImageTransferStatus.Cancelled -> Text(stringResource(Res.string.wifi_remote_transfer_cancelled))
                WifiImageTransferStatus.PermissionDenied -> Text(stringResource(Res.string.wifi_remote_storage_permission))
                WifiImageTransferStatus.Idle -> Unit
            }
            if (busy) OutlinedButton(onClick = { controller.cancelPhotoOperation(identifier) }) {
                Text(stringResource(Res.string.wifi_remote_transfer_cancel))
            }
            if (!browser.loading && browser.photos.isEmpty() && !browser.failed) {
                Text(stringResource(Res.string.wifi_remote_browser_empty))
            }
        }
        items(browser.photos, key = { it.handle }) { photo ->
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                thumbnails[photo.handle]?.let { image ->
                    Image(
                        image,
                        contentDescription = photo.filename,
                        modifier = Modifier.size(80.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(photo.filename)
                    Text(
                        stringResource(
                            Res.string.wifi_remote_photo_size,
                            (photo.size + 1023) / 1024
                        )
                    )
                    if (photo.capturedAt.isNotBlank()) Text(photo.capturedAt)
                    if (!photo.downloadable) Text(stringResource(Res.string.wifi_remote_download_too_large))
                }
                OutlinedButton(
                    onClick = { onDownload(photo.handle) },
                    enabled = !busy && photo.downloadable && photo.handle !in browser.savedHandles
                ) {
                    Text(
                        stringResource(
                            if (photo.handle in browser.savedHandles) Res.string.wifi_remote_download_saved
                            else Res.string.wifi_remote_download
                        )
                    )
                }
            }
        }
        item {
            if (browser.totalObjects > 0) Text(
                stringResource(
                    Res.string.wifi_remote_browser_page,
                    browser.offset / SonyImageTransfer.PAGE_SIZE + 1
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        controller.browsePhotos(
                            identifier,
                            maxOf(0, browser.offset - SonyImageTransfer.PAGE_SIZE)
                        )
                    },
                    enabled = !busy && browser.offset > 0
                ) { Text(stringResource(Res.string.wifi_remote_browser_previous)) }
                OutlinedButton(
                    onClick = {
                        controller.browsePhotos(
                            identifier,
                            browser.offset + SonyImageTransfer.PAGE_SIZE
                        )
                    },
                    enabled = !busy && (browser.hasMore || browser.offset + SonyImageTransfer.PAGE_SIZE < browser.totalObjects)
                ) {
                    Text(stringResource(Res.string.wifi_remote_browser_next))
                }
            }
        }
    }
}
