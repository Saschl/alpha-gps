package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionRegistry
import com.sasch.cameragps.sharednew.remote.bulbDurationSeconds
import com.sasch.cameragps.sharednew.remote.BulbExposure
import com.sasch.cameragps.sharednew.remote.BulbPhase
import com.sasch.cameragps.sharednew.remote.ShutterControls
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface WifiRemoteConnection {
    val cameraName: String
    val canCapture: Boolean
    val canCaptureBulb: Boolean get() = false
    suspend fun captureBulb(exposure: BulbExposure): WifiCaptureStatus = WifiCaptureStatus.Rejected
    val canTransferImages: Boolean get() = false
    val canConvertHeif: Boolean get() = false
    val photoHandlesSurviveModeSwitch: Boolean get() = true
    suspend fun openPhotoBrowser(): CameraPhotoPage = error("Photo browsing unavailable")
    suspend fun photoPage(offset: Int): CameraPhotoPage = error("Photo browsing unavailable")
    suspend fun photoThumbnail(handle: Long): ImageBitmap? = null
    suspend fun photoPreview(handle: Long): ImageBitmap? = null
    suspend fun downloadPhoto(
        handle: Long,
        format: WifiPhotoDownloadFormat,
        onProgress: (WifiImageTransferState) -> Unit
    ): WifiImageTransferState =
        WifiImageTransferState(WifiImageTransferStatus.Failed)

    suspend fun closePhotoBrowser() {}

    /** End transfer mode without reopening a shooting session during teardown. */
    suspend fun prepareToDisconnect() = closePhotoBrowser()
    val images: Flow<ImageBitmap>
    val lost: Flow<Unit>
    suspend fun capture(): WifiCaptureStatus
    suspend fun turnOffWifi(): WifiShutdownStatus = WifiShutdownStatus.NotRequested
    suspend fun close()
}

internal fun interface WifiRemoteConnector {
    val supportsAddressDiscovery: Boolean get() = false

    /** A blank host requests address discovery when supported. */
    suspend fun open(host: String, scope: CoroutineScope): WifiRemoteConnection
}

internal fun interface WifiAutomaticConnector {
    suspend fun open(identifier: String, scope: CoroutineScope, onPhase: (WifiRemotePhase) -> Unit): WifiRemoteConnection
}

internal interface WifiManualSetupConnector {
    suspend fun prepare(identifier: String): CameraWifiCredentials?
    suspend fun open(
        credentials: CameraWifiCredentials,
        scope: CoroutineScope
    ): WifiRemoteConnection
}

internal class WifiRemoteConnectException(
    val failure: WifiRemoteFailure,
    cause: Throwable? = null
) :
    Exception("Wi-Fi remote connection failed", cause)

data class WifiPhotoPreview(
    val handle: Long,
    val image: ImageBitmap? = null,
    val loading: Boolean = true
)

/** App-owned, Main.immediate-confined. Images stay separate from per-camera session state. */
class WifiRemoteController internal constructor(
    private val scope: CoroutineScope,
    private val registry: CameraSessionRegistry,
    private val connector: WifiRemoteConnector,
    private val claimControls: suspend (String) -> Unit,
    private val releaseControls: (String) -> Unit,
    private val automaticConnector: WifiAutomaticConnector? = null,
    private val manualSetupConnector: WifiManualSetupConnector? = null,
) : ShutterControls {
    private val log = logging()
    private var attempt = 0
    val supportsAutomaticConnection get() = automaticConnector != null
    val supportsManualSetup get() = manualSetupConnector != null
    val supportsAddressDiscovery get() = connector.supportsAddressDiscovery
    val sessions = registry.sessions
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image = _image.asStateFlow()
    private val _owner = MutableStateFlow<String?>(null)
    val owner = _owner.asStateFlow()
    private var job: Job? = null
    private var thumbnailCache = linkedMapOf<Long, ImageBitmap?>()
    private val _photoPreview = MutableStateFlow<WifiPhotoPreview?>(null)
    val photoPreview = _photoPreview.asStateFlow()
    private var photoPreviewJob: Job? = null
    private var sessionScope: CoroutineScope? = null
    private var activeConnection: WifiRemoteConnection? = null
    private var previewJob: Job? = null
    private var photoJob: Job? = null
    private var captureRequests: Channel<BulbExposure?>? = null
    private var bulbExposure: BulbExposure? = null
    private var manualJoin: CompletableDeferred<Unit>? = null

    fun connect(identifier: String, host: String) {
        start(identifier, WifiRemotePhase.OpeningSession) { _, sessionScope ->
            connector.open(host.trim(), sessionScope)
        }
    }

    fun connectAutomatically(identifier: String) {
        val automatic = automaticConnector ?: return
        start(identifier, WifiRemotePhase.PreparingCamera) { id, sessionScope ->
            automatic.open(id, sessionScope) { phase ->
                log.i { "Wi-Fi remote attempt=$attempt phase=$phase" }
                if (registry.get(id)?.wifiRemote?.phase != WifiRemotePhase.Closing) {
                    registry.updateWifiRemote(id, WifiRemoteState(phase = phase))
                }
            }
        }
    }

    fun prepareManualConnection(identifier: String) {
        val setup = manualSetupConnector ?: return
        start(identifier, WifiRemotePhase.PreparingCamera) { id, sessionScope ->
            val credentials = setup.prepare(id)
                ?: throw WifiRemoteConnectException(WifiRemoteFailure.CameraSetupFailed)
            currentCoroutineContext().ensureActive()
            val joined = CompletableDeferred<Unit>()
            manualJoin = joined
            try {
                registry.updateWifiRemote(
                    id, WifiRemoteState(
                        phase = WifiRemotePhase.AwaitingManualNetwork, manualNetwork = credentials
                    )
                )
                log.i { "Wi-Fi remote attempt=$attempt waiting for manual network join" }
                joined.await()
                setup.open(credentials, sessionScope)
            } finally {
                manualJoin = null
            }
        }
    }

    fun continueManualConnection(identifier: String) {
        if (_owner.value.equals(identifier, true) &&
            registry.get(identifier)?.wifiRemote?.phase == WifiRemotePhase.AwaitingManualNetwork
        ) {
            registry.updateWifiRemote(
                identifier,
                WifiRemoteState(phase = WifiRemotePhase.OpeningSession)
            )
            manualJoin?.complete(Unit)
        }
    }

    private fun start(identifier: String, phase: WifiRemotePhase,
                      open: suspend (String, CoroutineScope) -> WifiRemoteConnection) {
        if (registry.get(identifier)?.bulbTimer?.active == true) return
        if (job != null) {
            log.w { "Wi-Fi remote connect ignored: previous attempt=$attempt is still active or closing" }
            return
        }
        attempt++
        log.i { "Wi-Fi remote attempt=$attempt starting phase=$phase" }
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

    override fun capture(identifier: String): Boolean = requestCapture(identifier, null)

    override fun startBulbTimer(identifier: String, seconds: Int): Boolean {
        require(seconds in bulbDurationSeconds)
        if (activeConnection?.canCaptureBulb != true) return false
        val exposure = BulbExposure(seconds, { state ->
            registry.updateIfPresent(identifier) { it.copy(bulbTimer = state) }
        })
        return requestCapture(identifier, exposure)
    }

    override fun stopBulbTimer(identifier: String) {
        if (_owner.value.equals(identifier, true)) bulbExposure?.stop()
    }

    private fun requestCapture(identifier: String, exposure: BulbExposure?): Boolean {
        val id = identifier.uppercase()
        val state = registry.get(id)?.wifiRemote ?: return false
        if (_owner.value != id || state.phase != WifiRemotePhase.Ready || !state.canCaptureStill ||
            state.capture == WifiCaptureStatus.Shooting || state.photoBrowser.open || photoJob != null
        ) return false
        registry.updateWifiRemote(
            id,
            state.copy(
                capture = WifiCaptureStatus.Shooting,
                imageTransfer = WifiImageTransferState()
            )
        )
        bulbExposure = exposure
        exposure?.phase(BulbPhase.Starting)
        if (captureRequests?.trySend(exposure)?.isSuccess != true) {
            registry.updateWifiRemote(id, state)
            bulbExposure = null
            exposure?.phase(BulbPhase.Failed)
            return false
        }
        return true
    }

    fun browsePhotos(identifier: String) = loadPhotos(identifier, offset = null)

    fun loadMorePhotos(identifier: String) {
        val browser = registry.get(identifier)?.wifiRemote?.photoBrowser ?: return
        if (browser.open && browser.hasMore && !browser.loading) loadPhotos(identifier, browser.loadedCaptures)
    }

    private fun loadPhotos(identifier: String, offset: Int?) {
        val operation = if (offset == null) "open the photo catalog" else "load a photo page"
        photoWork(identifier, operation, { state ->
            val browser = if (offset == null) WifiPhotoBrowserState(savedDownloads = state.photoBrowser.savedDownloads)
                else state.photoBrowser
            state.copy(photoBrowser = browser.copy(open = true, loading = true, failed = false))
        }) { id, connection ->
            val page = if (offset == null) {
                previewJob?.cancelAndJoin()
                previewJob = null
                _image.value = null
                thumbnailCache = linkedMapOf()
                updateReady(id) { it.copy(hasLiveView = false) }
                connection.openPhotoBrowser()
            } else connection.photoPage(offset)
            val count = groupCameraPhotos(page.photos).size
            check(page.offset == (offset ?: 0) && (count > 0 || !page.hasMore)) { "Invalid camera photo page" }
            updateReady(id) {
                it.copy(photoBrowser = it.photoBrowser.copy(
                    photos = it.photoBrowser.photos + page.photos,
                    loadedCaptures = page.offset + count,
                    totalObjects = page.totalObjects,
                    hasMore = page.hasMore
                ))
            }
        }
    }

    internal suspend fun photoThumbnail(identifier: String, handle: Long): ImageBitmap? {
        val state = registry.get(identifier)?.wifiRemote ?: return null
        val connection = activeConnection ?: return null
        if (!_owner.value.equals(identifier, true) || !state.photoBrowser.open || state.imageTransfer.busy) return null
        val cache = thumbnailCache
        if (handle in cache) return cache.remove(handle).also { cache[handle] = it }
        val image = try {
            connection.photoThumbnail(handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.w(failure) { "Wi-Fi photo thumbnail unavailable" }
            null
        }
        currentCoroutineContext().ensureActive()
        if (cache !== thumbnailCache) return null
        cache[handle] = image
        if (cache.size > 24) cache.remove(cache.keys.first())
        return image
    }

    fun showPhotoPreview(identifier: String, handle: Long) {
        val id = identifier.uppercase()
        val state = registry.get(id)?.wifiRemote ?: return
        val connection = activeConnection ?: return
        val parent = sessionScope ?: return
        val capture = groupCameraPhotos(state.photoBrowser.photos)
            .firstOrNull { it.preview.handle == handle } ?: return
        if (_owner.value != id || state.phase != WifiRemotePhase.Ready ||
            !state.photoBrowser.open || state.imageTransfer.busy ||
            _photoPreview.value?.handle == handle
        ) return
        clearPhotoPreview(id)
        _photoPreview.value = WifiPhotoPreview(handle)
        photoPreviewJob = parent.launch {
            var image: ImageBitmap? = null
            for (photo in capture.previewCandidates) {
                currentCoroutineContext().ensureActive()
                image = try {
                    connection.photoPreview(photo.handle)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.w { "${photo.formatLabel} preview failed: ${failure.message}" }
                    null
                }
                currentCoroutineContext().ensureActive()
                if (image != null) break
                log.d { "${photo.formatLabel} preview unavailable; trying remaining capture formats" }
            }
            currentCoroutineContext().ensureActive()
            _photoPreview.value = WifiPhotoPreview(handle, image, loading = false)
            if (image == null) log.d { "Camera screen preview unavailable; retaining thumbnail" }
        }
    }

    fun clearPhotoPreview(identifier: String) {
        if (!_owner.value.equals(identifier, true)) return
        photoPreviewJob?.cancel()
        photoPreviewJob = null
        _photoPreview.value = null
    }

    fun downloadPhoto(identifier: String, handle: Long, format: WifiPhotoDownloadFormat = WifiPhotoDownloadFormat.Original) {
        downloadPhotos(identifier, listOf(WifiPhotoDownload(handle, format)))
    }

    fun downloadPhotos(identifier: String, downloads: List<WifiPhotoDownload>) {
        val state = registry.get(identifier)?.wifiRemote ?: return
        if (!state.photoBrowser.open) return
        val photos = state.photoBrowser.photos.associateBy { it.handle }
        val pending = downloads.distinct().filter { download ->
            val photo = photos[download.handle]
            photo != null && photo.downloadable && download !in state.photoBrowser.savedDownloads &&
                    (download.format == WifiPhotoDownloadFormat.Original ||
                            (state.canConvertHeif && photo.mimeType == "image/heif"))
        }
        if (pending.isEmpty()) return
        photoWork(
            identifier,
            "download photos",
            {
                it.copy(
                    imageTransfer = WifiImageTransferState(
                        WifiImageTransferStatus.Downloading,
                        totalFiles = pending.size
                    )
                )
            }) { id, connection ->
            for ((index, download) in pending.withIndex()) {
                currentCoroutineContext().ensureActive()
                updateReady(id) {
                    it.copy(
                        imageTransfer = WifiImageTransferState(
                            WifiImageTransferStatus.Downloading,
                            filename = photos.getValue(download.handle).filename,
                            completedFiles = index, totalFiles = pending.size
                        )
                    )
                }
                val result =
                    connection.downloadPhoto(download.handle, download.format) { progress ->
                        updateReady(id) {
                            it.copy(
                                imageTransfer = progress.copy(
                                    completedFiles = index,
                                    totalFiles = pending.size
                                )
                            )
                        }
                    }
                val saved = result.status == WifiImageTransferStatus.Saved
                updateReady(id) {
                    it.copy(
                        imageTransfer = result.copy(
                            status = if (saved && index < pending.lastIndex) WifiImageTransferStatus.Downloading else result.status,
                            completedFiles = index + if (saved) 1 else 0, totalFiles = pending.size
                        ),
                        photoBrowser = it.photoBrowser.copy(
                            savedDownloads = if (saved) it.photoBrowser.savedDownloads + download
                            else it.photoBrowser.savedDownloads
                        )
                    )
                }
                if (!saved) break
            }
        }
    }

    fun leavePhotoBrowser(identifier: String) {
        photoWork(
            identifier,
            "close the photo browser",
            {
                it.copy(
                    photoBrowser = it.photoBrowser.copy(
                        loading = true,
                        failed = false
                    )
                )
            }) { id, connection ->
            clearPhotoPreview(id)
            previewJob?.cancelAndJoin()
            previewJob = null
            connection.closePhotoBrowser()
            thumbnailCache = linkedMapOf()
            updateReady(id) {
                it.copy(
                    photoBrowser = WifiPhotoBrowserState(
                        savedDownloads =
                            if (connection.photoHandlesSurviveModeSwitch) it.photoBrowser.savedDownloads else emptySet()
                    ),
                    canCaptureStill = connection.canCapture,
                    canTransferImages = connection.canTransferImages,
                    imageTransfer = WifiImageTransferState()
                )
            }
            startPreview(id, connection, sessionScope!!)
        }
    }

    fun cancelPhotoOperation(identifier: String) {
        if (_owner.value.equals(identifier, true)) photoJob?.cancel()
    }

    fun clearPhotoTransferResult(identifier: String) {
        if (!_owner.value.equals(identifier, true)) return
        updateReady(identifier.uppercase()) {
            if (it.imageTransfer.busy) it else it.copy(imageTransfer = WifiImageTransferState())
        }
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
        identifier: String,
        operation: String,
        starting: (WifiRemoteState) -> WifiRemoteState,
        action: suspend (String, WifiRemoteConnection) -> Unit
    ) {
        val id = identifier.uppercase()
        val state = registry.get(id)?.wifiRemote ?: return
        val connection = activeConnection ?: return
        val parent = sessionScope ?: return
        if (_owner.value != id || state.phase != WifiRemotePhase.Ready || !state.canTransferImages ||
            state.capture == WifiCaptureStatus.Shooting || photoJob != null
        ) return
        photoPreviewJob?.cancel()
        photoPreviewJob = null
        _photoPreview.value =
            _photoPreview.value?.takeIf { it.image != null }?.copy(loading = false)
        registry.updateWifiRemote(id, starting(state))
        val work = parent.launch(start = CoroutineStart.LAZY) {
            try {
                action(id, connection)
            } catch (failure: Exception) {
                parent.coroutineContext.ensureActive()
                val cancelled = !isActive || (failure is CancellationException && failure !is TimeoutCancellationException)
                if (!cancelled) log.e(failure) { "Failed to $operation over Wi-Fi" }
                updateReady(id) {
                    it.copy(
                        photoBrowser = it.photoBrowser.copy(failed = !cancelled || it.photoBrowser.loading),
                        imageTransfer = if (it.imageTransfer.busy) it.imageTransfer.copy(
                            status = if (cancelled) WifiImageTransferStatus.Cancelled else WifiImageTransferStatus.Failed
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
                        imageTransfer = if (it.imageTransfer.busy) it.imageTransfer.copy(
                            status = WifiImageTransferStatus.Cancelled
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
        log.i { "Wi-Fi remote attempt=$attempt disconnect requested" }
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
            log.d { "Wi-Fi remote attempt=$attempt claiming BLE remote controls" }
            claimControls(id)
            log.d { "Wi-Fi remote attempt=$attempt BLE remote controls claimed" }
            coroutineScope {
                val opened = open(id, this)
                connection = opened
                log.i { "Wi-Fi remote attempt=$attempt ready; starting live view" }
                val requests = Channel<BulbExposure?>(Channel.RENDEZVOUS)
                captureRequests = requests
                registry.updateWifiRemote(id, WifiRemoteState(phase = WifiRemotePhase.Ready,
                    canCaptureStill = opened.canCapture, cameraName = opened.cameraName,
                    canCaptureBulb = opened.canCaptureBulb,
                    canTransferImages = opened.canTransferImages,
                    canConvertHeif = opened.canConvertHeif
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
                    var confirmationTimer: Job? = null
                    for (exposure in requests) {
                        confirmationTimer?.cancel()
                        if (exposure != null) {
                            previewJob?.cancelAndJoin()
                            updateReady(id) { it.copy(hasLiveView = false, preview = WifiPreviewStatus.PausedForBulb) }
                        }
                        val result = try {
                            if (exposure == null) opened.capture() else opened.captureBulb(exposure)
                        } catch (cancelled: CancellationException) {
                            exposure?.phase(BulbPhase.Uncertain)
                            throw cancelled
                        }
                        catch (_: Exception) { WifiCaptureStatus.Uncertain }
                        if (exposure != null) startPreview(id, opened, this@coroutineScope)
                        exposure?.phase(
                            when (result) {
                                WifiCaptureStatus.Captured, WifiCaptureStatus.Idle -> BulbPhase.Finished
                                WifiCaptureStatus.Rejected -> BulbPhase.Failed
                                else -> BulbPhase.Uncertain
                            }
                        )
                        bulbExposure = null
                        updateReady(id) { it.copy(capture = result) }
                        confirmationTimer = if (result == WifiCaptureStatus.Captured) launch {
                            delay(3.seconds)
                            updateReady(id) {
                                if (it.capture == WifiCaptureStatus.Captured) it.copy(capture = WifiCaptureStatus.Idle)
                                else it
                            }
                        } else null
                    }
                }
                awaitCancellation()
            }
        } catch (cancelled: CancellationException) {
            log.d { "Wi-Fi remote attempt=$attempt cancelled" }
            throw cancelled
        } catch (failed: WifiRemoteConnectException) {
            failure = failed.failure
            log.w { "Wi-Fi remote attempt=$attempt failed: $failure" }
        } catch (failed: Exception) {
            failure = WifiRemoteFailure.ProtocolError
            log.w { "Wi-Fi remote attempt=$attempt failed: ${failed.message}" }
        }
        finally {
            bulbExposure?.phase(BulbPhase.Uncertain)
            bulbExposure = null
            sessionScope = null
            activeConnection = null
            previewJob = null
            photoJob = null
            photoPreviewJob = null
            _photoPreview.value = null
            thumbnailCache = linkedMapOf()
            captureRequests?.close()
            captureRequests = null
            withContext(NonCancellable) {
                try {
                    if (failure == null) {
                        try {
                            withTimeoutOrNull(5.seconds) { connection?.prepareToDisconnect() }
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
                        log.i { "Wi-Fi remote attempt=$attempt cleanup complete; failure=$failure shutdown=$shutdown" }
                    }
                }
            }
        }
    }

    private fun startPreview(id: String, connection: WifiRemoteConnection, parent: CoroutineScope) {
        updateReady(id) { it.copy(hasLiveView = false, preview = WifiPreviewStatus.Waiting) }
        previewJob = parent.launch {
            var receivedFrame = false
            log.d { "Wi-Fi live view starting" }
            try {
                connection.images.collect { image ->
                    if (!receivedFrame) {
                        log.i { "Wi-Fi live view first image ${image.width}x${image.height}" }
                        receivedFrame = true
                    }
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
                log.w { "Wi-Fi live view timed out: ${cancelled.message}" }
                updateReady(id) { it.copy(hasLiveView = false, preview = WifiPreviewStatus.Failed) }
            } catch (failure: Exception) {
                log.w { "Wi-Fi live view failed: ${failure.message}" }
                updateReady(id) { it.copy(hasLiveView = false, preview = WifiPreviewStatus.Failed) }
            } finally {
                log.i { "Wi-Fi live view stopped; receivedFrame=$receivedFrame" }
                _image.value = null
            }
        }
    }

    private fun updateReady(id: String, update: (WifiRemoteState) -> WifiRemoteState) {
        val state = registry.get(id)?.wifiRemote ?: return
        if (state.phase == WifiRemotePhase.Ready) registry.updateWifiRemote(id, update(state))
    }
}
