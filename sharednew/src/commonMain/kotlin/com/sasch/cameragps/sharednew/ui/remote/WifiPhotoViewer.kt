package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.arrow_back_24px
import cameragps.sharednew.generated.resources.wifi_remote_browser_empty
import cameragps.sharednew.generated.resources.wifi_remote_browser_failed
import cameragps.sharednew.generated.resources.wifi_remote_browser_loading
import cameragps.sharednew.generated.resources.wifi_remote_browser_next
import cameragps.sharednew.generated.resources.wifi_remote_browser_previous
import cameragps.sharednew.generated.resources.wifi_remote_browser_title
import cameragps.sharednew.generated.resources.wifi_remote_preview_unavailable
import cameragps.sharednew.generated.resources.wifi_remote_transfer_cancel
import cameragps.sharednew.generated.resources.wifi_remote_viewer_back
import cameragps.sharednew.generated.resources.wifi_remote_viewer_details
import cameragps.sharednew.generated.resources.wifi_remote_viewer_position
import cameragps.sharednew.generated.resources.wifi_remote_viewer_retry
import cameragps.sharednew.generated.resources.wifi_remote_viewer_transfer_wait
import com.sasch.cameragps.sharednew.remote.wifi.WifiPhotoDownload
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import com.sasch.cameragps.sharednew.remote.wifi.groupCameraPhotos
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WifiPhotoViewer(
    state: WifiRemoteState,
    viewModel: WifiRemoteViewModel,
    onDownload: (List<WifiPhotoDownload>) -> Unit,
) {
    val browser = state.photoBrowser
    val captures = remember(browser.photos) { groupCameraPhotos(browser.photos) }
    val thumbnails by viewModel.controller.thumbnails.collectAsState()
    key(browser.offset) {
        val preceding = if (browser.offset > 0) 1 else 0
        val count = captures.size + preceding + if (browser.hasMore) 1 else 0
        val initialPage =
            ((viewModel.viewedPhotoIndex ?: browser.offset) - browser.offset + preceding)
                .coerceIn(0, maxOf(0, count - 1))
        val pager = rememberPagerState(initialPage = initialPage) { count }
        val scope = rememberCoroutineScope()
        var showDetails by rememberSaveable { mutableStateOf(false) }
        val current = captures.getOrNull(pager.currentPage - preceding)
        val index = browser.offset + pager.currentPage - preceding
        val changingPage = browser.loading && (viewModel.viewedPhotoIndex ?: browser.offset) !in
                browser.offset until browser.offset + captures.size

        LaunchedEffect(
            pager.settledPage,
            browser.loading,
            browser.failed,
            state.imageTransfer.busy
        ) {
            if (captures.isNotEmpty()) {
                val settledIndex = pager.settledPage - preceding
                if (settledIndex in captures.indices || !browser.failed) {
                    viewModel.viewPhotoAt(browser.offset + settledIndex)
                }
            }
        }
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(
                Modifier.fillMaxSize().background(Color.Black)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = viewModel::closePhoto) {
                        Icon(
                            painterResource(Res.drawable.arrow_back_24px),
                            contentDescription = stringResource(Res.string.wifi_remote_viewer_back),
                            tint = Color.White
                        )
                    }
                    Text(
                        current?.name ?: stringResource(Res.string.wifi_remote_browser_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (current != null) Text(
                        stringResource(
                            Res.string.wifi_remote_viewer_position,
                            index + 1, browser.totalObjects
                        ), style = MaterialTheme.typography.labelLarge
                    )
                }
                if (captures.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(Res.string.wifi_remote_browser_empty))
                    }
                } else HorizontalPager(
                    state = pager, modifier = Modifier.weight(1f).fillMaxWidth(),
                    userScrollEnabled = !changingPage,
                    key = { page -> captures.getOrNull(page - preceding)?.id ?: "boundary:$page" },
                ) { page ->
                    val capture = captures.getOrNull(page - preceding)
                    val image = capture?.let { thumbnails[it.preview.handle] }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when {
                            capture == null -> Column(
                                Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                when {
                                    browser.failed -> {
                                        Text(stringResource(Res.string.wifi_remote_browser_failed))
                                        TextButton(
                                            onClick = { viewModel.viewPhotoAt(browser.offset + page - preceding) },
                                            enabled = !browser.loading && !state.imageTransfer.busy
                                        ) {
                                            Text(stringResource(Res.string.wifi_remote_viewer_retry))
                                        }
                                    }

                                    state.imageTransfer.busy -> Text(stringResource(Res.string.wifi_remote_viewer_transfer_wait))
                                    else -> {
                                        CircularProgressIndicator(color = Color.White)
                                        Text(stringResource(Res.string.wifi_remote_browser_loading))
                                    }
                                }
                            }

                            image != null -> Image(
                                image, contentDescription = capture.name,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
                            )

                            browser.loading -> CircularProgressIndicator(color = Color.White)
                            else -> Text(stringResource(Res.string.wifi_remote_preview_unavailable))
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PhotoTransferStatus(state.imageTransfer)
                    if (state.imageTransfer.busy || browser.loading) {
                        TextButton(onClick = { viewModel.controller.cancelPhotoOperation(viewModel.identifier) }) {
                            Text(stringResource(Res.string.wifi_remote_transfer_cancel))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        enabled = pager.currentPage > 0 && !changingPage && !pager.isScrollInProgress
                    ) {
                        Text(stringResource(Res.string.wifi_remote_browser_previous))
                    }
                    TextButton(onClick = { showDetails = true }, enabled = current != null) {
                        Text(stringResource(Res.string.wifi_remote_viewer_details))
                    }
                    TextButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        enabled = pager.currentPage < count - 1 && !changingPage && !pager.isScrollInProgress
                    ) {
                        Text(stringResource(Res.string.wifi_remote_browser_next))
                    }
                }
            }
        }
        if (showDetails && current != null) {
            PhotoDetailsSheet(
                current, thumbnails[current.preview.handle], state,
                onDismiss = { showDetails = false },
                onDownload = { handle, format ->
                    onDownload(
                        listOf(
                            WifiPhotoDownload(
                                handle,
                                format
                            )
                        )
                    )
                },
                onCancel = { viewModel.controller.cancelPhotoOperation(viewModel.identifier) })
        }
    }
}
