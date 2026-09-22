package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal interface WifiRemoteConnection {
    val cameraName: String
    val canCapture: Boolean
    val canTransferImages: Boolean get() = false
    suspend fun openPhotoBrowser(): CameraPhotoPage = error("Photo browsing unavailable")
    suspend fun photoPage(offset: Int): CameraPhotoPage = error("Photo browsing unavailable")
    suspend fun photoThumbnail(handle: Long): ImageBitmap? = null
    suspend fun downloadPhoto(
        handle: Long,
        onProgress: (WifiImageTransferState) -> Unit
    ): WifiImageTransferState =
        WifiImageTransferState(WifiImageTransferStatus.Failed)

    suspend fun closePhotoBrowser() {}
    val images: Flow<ImageBitmap>
    val lost: Flow<Unit>
    suspend fun capture(): WifiCaptureStatus
    suspend fun turnOffWifi(): WifiShutdownStatus = WifiShutdownStatus.NotRequested
    suspend fun close()
}

internal fun interface WifiRemoteConnector {
    suspend fun open(host: String, scope: CoroutineScope): WifiRemoteConnection
}

internal fun interface WifiAutomaticConnector {
    suspend fun open(identifier: String, scope: CoroutineScope, onPhase: (WifiRemotePhase) -> Unit): WifiRemoteConnection
}

internal class WifiRemoteConnectException(val failure: WifiRemoteFailure) : Exception("Wi-Fi remote connection failed")

/** App-owned, Main.immediate-confined. Images stay separate from per-camera session state. */
class WifiRemoteController internal constructor(
    private val scope: CoroutineScope,
    private val registry: CameraSessionRegistry,
    private val connector: WifiRemoteConnector,
    private val claimControls: suspend (String) -> Unit,
    private val releaseControls: (String) -> Unit,
    private val automaticConnector: WifiAutomaticConnector? = null,
) {
    val supportsAutomaticConnection get() = automaticConnector != null
    val sessions = registry.sessions
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image = _image.asStateFlow()
    private val _owner = MutableStateFlow<String?>(null)
    val owner = _owner.asStateFlow()
    private var job: Job? = null
    private val _thumbnails = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val thumbnails = _thumbnails.asStateFlow()
    private var sessionScope: CoroutineScope? = null
    private var activeConnection: WifiRemoteConnection? = null
    private var previewJob: Job? = null
    private var photoJob: Job? = null
    private var captureRequests: Channel<Unit>? = null

    fun connect(identifier: String, host: String) {
        start(identifier, WifiRemotePhase.OpeningSession) { _, sessionScope ->
            connector.open(host.trim(), sessionScope)
        }
    }

    fun connectAutomatically(identifier: String) {
        val automatic = automaticConnector ?: return
        start(identifier, WifiRemotePhase.PreparingCamera) { id, sessionScope ->
            automatic.open(id, sessionScope) { phase ->
                if (registry.get(id)?.wifiRemote?.phase != WifiRemotePhase.Closing) {
                    registry.updateWifiRemote(id, WifiRemoteState(phase = phase))
                }
            }
        }
    }

    private fun start(identifier: String, phase: WifiRemotePhase,
                      open: suspend (String, CoroutineScope) -> WifiRemoteConnection) {
        if (job != null) return
        val id = identifier.uppercase()
        if (registry.get(id) == null) registry.upsert(id) { it.copy(phase = BleSessionPhase.Disconnected) }
        _owner.value = id
        _image.value = null
        registry.updateWifiRemote(id, WifiRemoteState(phase = phase))
        var entered = false
        val connecting = scope.launch(start = CoroutineStart.LAZY) {
            entered = true
            runSession(id, open)
        }
        job = connecting
        connecting.invokeOnCompletion {
            if (!entered && job === connecting) {
                registry.updateWifiRemote(id, WifiRemoteState())
                _owner.value = null
                job = null
            }
        }
        connecting.start()
    }

    fun capture(identifier: String) {
        val id = identifier.uppercase()
        val state = registry.get(id)?.wifiRemote ?: return
        if (_owner.value != id || state.phase != WifiRemotePhase.Ready || !state.canCaptureStill ||
            state.capture == WifiCaptureStatus.Shooting || state.photoBrowser.open || photoJob != null
        ) return
        registry.updateWifiRemote(
            id,
            state.copy(
                capture = WifiCaptureStatus.Shooting,
                imageTransfer = WifiImageTransferState()
            )
        )
        if (captureRequests?.trySend(Unit)?.isSuccess != true) registry.updateWifiRemote(id, state)
    }

    fun browsePhotos(identifier: String, offset: Int? = null) {
        photoWork(identifier, { state ->
            state.copy(
                photoBrowser = state.photoBrowser.copy(
                    open = true, loading = true, failed = false, photos = emptyList()
                )
            )
        }) { id, connection ->
            previewJob?.cancelAndJoin()
            previewJob = null
            _image.value = null
            _thumbnails.value = emptyMap()
            updateReady(id) { it.copy(hasLiveView = false) }
            val page =
                if (offset == null) connection.openPhotoBrowser() else connection.photoPage(offset)
            updateReady(id) {
                it.copy(
                    photoBrowser = it.photoBrowser.copy(
                        photos = page.photos,
                        offset = page.offset,
                        totalObjects = page.totalObjects,
                        hasMore = page.hasMore
                    )
                )
            }
            for (photo in page.photos) {
                connection.photoThumbnail(photo.handle)
                    ?.let { _thumbnails.value += photo.handle to it }
            }
        }
    }

    fun downloadPhoto(identifier: String, handle: Long) {
        val state = registry.get(identifier)?.wifiRemote ?: return
        if (!state.photoBrowser.open || handle in state.photoBrowser.savedHandles ||
            state.photoBrowser.photos.none { it.handle == handle && it.downloadable }
        ) return
        photoWork(
            identifier,
            { it.copy(imageTransfer = WifiImageTransferState(WifiImageTransferStatus.Downloading)) }) { id, connection ->
            val result = connection.downloadPhoto(handle) { progress ->
                updateReady(id) {
                    it.copy(imageTransfer = progress)
                }
            }
            updateReady(id) {
                it.copy(
                    imageTransfer = result, photoBrowser = it.photoBrowser.copy(
                        savedHandles = if (result.status == WifiImageTransferStatus.Saved) it.photoBrowser.savedHandles + handle
                        else it.photoBrowser.savedHandles
                    )
                )
            }
        }
    }

    fun leavePhotoBrowser(identifier: String) {
        photoWork(
            identifier,
            {
                it.copy(
                    photoBrowser = it.photoBrowser.copy(
                        loading = true,
                        failed = false
                    )
                )
            }) { id, connection ->
            previewJob?.cancelAndJoin()
            previewJob = null
            connection.closePhotoBrowser()
            _thumbnails.value = emptyMap()
            updateReady(id) {
                it.copy(
                    photoBrowser = WifiPhotoBrowserState(savedHandles = it.photoBrowser.savedHandles),
                    imageTransfer = WifiImageTransferState(), preview = WifiPreviewStatus.Waiting
                )
            }
            startPreview(id, connection, sessionScope!!)
        }
    }

    fun cancelPhotoOperation(identifier: String) {
        if (_owner.value.equals(identifier, true)) photoJob?.cancel()
    }

    fun imageStoragePermissionDenied(identifier: String) {
        updateReady(identifier.uppercase()) {
            it.copy(
                imageTransfer = WifiImageTransferState(
                    WifiImageTransferStatus.PermissionDenied
                )
            )
        }
    }

    private fun photoWork(
        identifier: String, starting: (WifiRemoteState) -> WifiRemoteState,
        action: suspend (String, WifiRemoteConnection) -> Unit
    ) {
        val id = identifier.uppercase()
        val state = registry.get(id)?.wifiRemote ?: return
        val connection = activeConnection ?: return
        val parent = sessionScope ?: return
        if (_owner.value != id || state.phase != WifiRemotePhase.Ready || !state.canTransferImages ||
            state.capture == WifiCaptureStatus.Shooting || photoJob != null
        ) return
        registry.updateWifiRemote(id, starting(state))
        val work = parent.launch(start = CoroutineStart.LAZY) {
            try {
                action(id, connection)
            } catch (_: TimeoutCancellationException) {
                updateReady(id) {
                    it.copy(
                        photoBrowser = it.photoBrowser.copy(failed = true),
                        imageTransfer = if (it.imageTransfer.busy) WifiImageTransferState(
                            WifiImageTransferStatus.Failed
                        ) else it.imageTransfer
                    )
                }
            } catch (cancelled: CancellationException) {
                parent.coroutineContext.ensureActive()
                updateReady(id) {
                    it.copy(
                        photoBrowser = it.photoBrowser.copy(failed = it.photoBrowser.loading),
                        imageTransfer = if (it.imageTransfer.busy)
                            WifiImageTransferState(WifiImageTransferStatus.Cancelled) else it.imageTransfer
                    )
                }
            } catch (_: Exception) {
                updateReady(id) {
                    it.copy(
                        photoBrowser = it.photoBrowser.copy(failed = true),
                        imageTransfer = if (it.imageTransfer.busy) WifiImageTransferState(
                            WifiImageTransferStatus.Failed
                        ) else it.imageTransfer
                    )
                }
            } finally {
                updateReady(id) { it.copy(photoBrowser = it.photoBrowser.copy(loading = false)) }
            }
        }
        photoJob = work
        work.invokeOnCompletion {
            if (photoJob === work) {
                photoJob = null
                updateReady(id) {
                    it.copy(
                        photoBrowser = it.photoBrowser.copy(loading = false),
                        imageTransfer = if (it.imageTransfer.busy) WifiImageTransferState(
                            WifiImageTransferStatus.Cancelled
                        ) else it.imageTransfer
                    )
                }
            }
        }
        work.start()
    }

    fun disconnect(identifier: String? = null) {
        val id = _owner.value ?: return
        if (identifier != null && !id.equals(identifier, true)) return
        registry.updateWifiRemote(id, WifiRemoteState(phase = WifiRemotePhase.Closing))
        job?.cancel()
    }

    suspend fun closeAndJoin(identifier: String? = null) {
        if (identifier != null && !_owner.value.equals(identifier, true)) return
        val closing = job
        disconnect(identifier)
        closing?.join()
    }

    fun permissionDenied(identifier: String) {
        if (job != null) return
        if (registry.get(identifier) == null) registry.upsert(identifier) { it.copy(phase = BleSessionPhase.Disconnected) }
        registry.updateWifiRemote(identifier, WifiRemoteState(WifiRemotePhase.Failed, WifiRemoteFailure.NetworkPermissionDenied))
    }

    private suspend fun runSession(id: String, open: suspend (String, CoroutineScope) -> WifiRemoteConnection) {
        var connection: WifiRemoteConnection? = null
        var failure: WifiRemoteFailure? = null
        var shutdown = WifiShutdownStatus.NotRequested
        try {
            claimControls(id)
            coroutineScope {
                val opened = open(id, this)
                connection = opened
                val requests = Channel<Unit>(Channel.RENDEZVOUS)
                captureRequests = requests
                registry.updateWifiRemote(id, WifiRemoteState(phase = WifiRemotePhase.Ready,
                    canCaptureStill = opened.canCapture, cameraName = opened.cameraName,
                    canTransferImages = opened.canTransferImages
                )
                )
                launch {
                    opened.lost.first()
                    throw WifiRemoteConnectException(WifiRemoteFailure.NetworkLost)
                }
                sessionScope = this
                activeConnection = opened
                startPreview(id, opened, this)
                launch(start = CoroutineStart.UNDISPATCHED) {
                    for (ignored in requests) {
                        val result = try { opened.capture() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { WifiCaptureStatus.Uncertain }
                        updateReady(id) { it.copy(capture = result) }
                    }
                }
                awaitCancellation()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failed: WifiRemoteConnectException) { failure = failed.failure }
        catch (_: Exception) { failure = WifiRemoteFailure.ProtocolError }
        finally {
            sessionScope = null
            activeConnection = null
            previewJob = null
            photoJob = null
            _thumbnails.value = emptyMap()
            captureRequests?.close()
            captureRequests = null
            withContext(NonCancellable) {
                try {
                    if (failure == null) {
                        try {
                            withTimeoutOrNull(5.seconds) { connection?.closePhotoBrowser() }
                        } catch (_: Exception) { /* Socket closure also ends transfer mode. */
                        }
                    }
                    if (connection != null && failure == null && registry.get(id)?.wifiRemote?.phase == WifiRemotePhase.Closing) {
                        shutdown = try {
                            connection.turnOffWifi()
                        } catch (_: Exception) {
                            WifiShutdownStatus.Unconfirmed
                        }
                    }
                } finally {
                    try {
                        connection?.close()
                    } catch (_: Exception) { /* Best-effort socket teardown. */
                    } finally {
                        _image.value = null
                        registry.updateWifiRemote(
                            id, if (failure == null) WifiRemoteState(wifiShutdown = shutdown)
                            else WifiRemoteState(WifiRemotePhase.Failed, failure)
                        )
                        releaseControls(id)
                        _owner.value = null
                        job = null
                    }
                }
            }
        }
    }

    private fun startPreview(id: String, connection: WifiRemoteConnection, parent: CoroutineScope) {
        previewJob = parent.launch {
            try {
                connection.images.collect { image ->
                    _image.value = image
                    updateReady(id) {
                        it.copy(
                            hasLiveView = true,
                            preview = WifiPreviewStatus.Streaming
                        )
                    }
                }
                updateReady(id) { it.copy(hasLiveView = false, preview = WifiPreviewStatus.Failed) }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                updateReady(id) { it.copy(hasLiveView = false, preview = WifiPreviewStatus.Failed) }
            } catch (_: Exception) {
                updateReady(id) { it.copy(hasLiveView = false, preview = WifiPreviewStatus.Failed) }
            } finally {
                _image.value = null
            }
        }
    }

    private fun updateReady(id: String, update: (WifiRemoteState) -> WifiRemoteState) {
        val state = registry.get(id)?.wifiRemote ?: return
        if (state.phase == WifiRemotePhase.Ready) registry.updateWifiRemote(id, update(state))
    }
}
