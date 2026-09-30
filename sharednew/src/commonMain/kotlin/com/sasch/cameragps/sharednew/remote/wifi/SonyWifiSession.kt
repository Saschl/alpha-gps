package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import com.diamondedge.logging.logging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.random.Random

/** The platform supplies network mechanics; Sony session behavior is shared. */
internal suspend fun openSonyWifiSession(
    connections: PtpIpConnectionFactory,
    scope: CoroutineScope,
    host: String,
    http: SonyLiveViewHttpTransport,
    imageStore: CameraImageStore,
    decodeThumbnail: (ByteArray) -> ImageBitmap?,
    networkLost: Flow<Unit> = emptyFlow(),
    releaseNetwork: () -> Unit = {},
): WifiRemoteConnection {
    val log = logging("SonyWifiSession")
    val protocolScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    var session: PtpIpOpenedSession? = null
    try {
        val opened = PtpIpSessionOpener(connections, protocolScope)
            .open(Random.nextBytes(16), "Alpha GPS")
            ?: throw WifiRemoteConnectException(WifiRemoteFailure.CameraUnavailable)
        session = opened
        log.i { "Wi-Fi PTP ready; starting Sony initialization" }
        val ready = when (val init = opened.initializeSony()) {
            is SonyPtpInitializationResult.Ready -> init.also { log.i { "Wi-Fi Sony initialized: $it, descriptionBytes=${it.deviceDescription?.size ?: 0}" } }
            is SonyPtpInitializationResult.Rejected -> {
                log.w { "Wi-Fi Sony initialization rejected: $init" }
                throw WifiRemoteConnectException(WifiRemoteFailure.CameraRefused)
            }
            else -> {
                log.w { "Wi-Fi Sony initialization failed: $init" }
                throw WifiRemoteConnectException(WifiRemoteFailure.ProtocolError)
            }
        }
        val transfer = SonyImageTransfer(
            opened.commands, ready, opened.events,
            decodeThumbnail, imageStore
        )
        val shutter = SonyPtpShutter(opened.commands, ready, opened.events)
        val preview = SonyLiveViewStream(opened.commands, ready, opened.events,
            http)
        return object : WifiRemoteConnection {
            override val cameraName = opened.camera.cameraName
            override val canTransferImages = transfer.supported
            override val canConvertHeif = imageStore.canConvertHeif
            override suspend fun openPhotoBrowser() = transfer.openBrowser()
            override suspend fun photoPage(offset: Int) = transfer.page(offset)
            override suspend fun photoThumbnail(handle: Long) = transfer.thumbnail(handle)
            override suspend fun downloadPhoto(
                handle: Long,
                format: WifiPhotoDownloadFormat,
                onProgress: (WifiImageTransferState) -> Unit
            ) =
                transfer.download(handle, format, onProgress)

            override suspend fun closePhotoBrowser() = transfer.closeBrowser()
            override val canCapture = ready.deviceInfo.supports(SonyPtpOperation.SDIO_CONTROL_DEVICE) &&
                ready.extendedInfo.supportsControl(SonyPtpControlCode.HALF_PRESS) &&
                ready.extendedInfo.supportsControl(SonyPtpControlCode.FULL_PRESS)
            override val images = flow {
                log.i { "Wi-Fi live view resolving camera endpoint" }
                val dd = ready.deviceDescription ?: error("Camera has no preview endpoint")
                emitAll(preview.images(SonyLiveViewEndpoint.fromDeviceDescription(dd, host)))
            }
            override val lost = merge(networkLost, opened.events.isClosed.filter { it }.map { Unit })
            override suspend fun capture() = when (shutter.captureStill()) {
                SonyPtpCaptureResult.CaptureEventObserved -> WifiCaptureStatus.Captured
                is SonyPtpCaptureResult.Rejected, SonyPtpCaptureResult.Unsupported -> WifiCaptureStatus.Rejected
                else -> WifiCaptureStatus.Uncertain
            }
            override suspend fun turnOffWifi() =
                SonyWifiShutdown(opened.commands, ready).request()
            override suspend fun close() {
                try { opened.close() }
                finally { protocolScope.cancel(); releaseNetwork() }
            }
        }
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            try { session?.close() }
            finally { protocolScope.cancel(); releaseNetwork() }
        }
        throw failure
    }
}
