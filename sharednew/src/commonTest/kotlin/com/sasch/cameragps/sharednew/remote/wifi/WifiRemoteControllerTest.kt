package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WifiRemoteControllerTest {
    private class Connection : WifiRemoteConnection {
        override val cameraName = "Test camera"
        override val canCapture = true
        override val canTransferImages = true
        var browsing = false
        var downloads = 0
        var photos = listOf(WifiCameraPhoto(42L, "DSC.JPG", 100L, "image/jpeg", ""))
        val thumbnailRequests = mutableListOf<Long>()
        val downloadedHandles = mutableListOf<Long>()
        var failBrowserExit = false
        val downloadResult = CompletableDeferred<WifiImageTransferState>()
        override suspend fun openPhotoBrowser(): CameraPhotoPage {
            browsing = true
            cleanup += "browse"
            return CameraPhotoPage(
                photos,
                0,
                1
            )
        }

        override suspend fun photoThumbnail(handle: Long): ImageBitmap? {
            thumbnailRequests += handle
            return null
        }

        override suspend fun downloadPhoto(
            handle: Long,
            onProgress: (WifiImageTransferState) -> Unit
        ): WifiImageTransferState {
            downloads++
            downloadedHandles += handle
            return try {
                downloadResult.await().copy(filename = photos.first { it.handle == handle }.filename)
            } finally {
                cleanup += "download"
            }
        }

        override suspend fun closePhotoBrowser() {
            if (!browsing) return
            check(!failBrowserExit)
            cleanup += "browse-close"
            browsing = false
        }
        val cleanup = mutableListOf<String>()
        val losses = Channel<Unit>(Channel.CONFLATED)
        val result = CompletableDeferred<WifiCaptureStatus>()
        var captures = 0
        var shutdownEnabled = false
        override val images: Flow<ImageBitmap> = flow {
            try { awaitCancellation() } finally { cleanup += "preview" }
        }
        override val lost = losses.receiveAsFlow()
        override suspend fun capture(): WifiCaptureStatus {
            captures++
            return try { result.await() } finally { cleanup += "capture" }
        }
        override suspend fun close() { cleanup += "connection" }
        override suspend fun turnOffWifi(): WifiShutdownStatus {
            if (!shutdownEnabled) return WifiShutdownStatus.NotRequested
            cleanup += "wifi-off"
            return WifiShutdownStatus.Requested
        }
    }

    @Test
    fun disconnectFinishesCaptureAndPreviewCleanupBeforeClosingConnection() = runTest {
        val registry = CameraSessionRegistry()
        val connection = Connection()
        val controller = WifiRemoteController(backgroundScope, registry, { _, _ -> connection }, {}, { connection.cleanup += "ble" })
        controller.connect("camera", "127.0.0.1")
        runCurrent()
        assertEquals(WifiRemotePhase.Ready, registry.get("camera")?.wifiRemote?.phase)
        controller.capture("camera")
        controller.capture("camera")
        runCurrent()
        assertEquals(1, connection.captures)
        controller.disconnect("camera")
        runCurrent()
        assertEquals(setOf("preview", "capture"), connection.cleanup.take(2).toSet())
        assertEquals(listOf("connection", "ble"), connection.cleanup.takeLast(2))
        assertNull(controller.owner.value)
        assertNull(registry.get("camera"))
    }

    @Test
    fun failedOpenCanRetryAndNeverCreatesDuplicateSessions() = runTest {
        val registry = CameraSessionRegistry()
        var opens = 0
        val controller = WifiRemoteController(backgroundScope, registry, { _, _ ->
            opens++
            throw WifiRemoteConnectException(WifiRemoteFailure.JoinCameraWifi)
        }, {}, {})
        controller.connect("camera", "127.0.0.1")
        runCurrent()
        assertEquals(WifiRemoteFailure.JoinCameraWifi, registry.get("camera")?.wifiRemote?.failure)
        assertNull(controller.owner.value)
        controller.connect("camera", "127.0.0.1")
        controller.connect("other", "127.0.0.1")
        runCurrent()
        assertEquals(2, opens)
        assertNull(registry.get("other"))
    }

    @Test
    fun reportsConfirmedCaptureAndClosesOnNetworkLossWithoutRetry() = runTest {
        val registry = CameraSessionRegistry()
        val connection = Connection()
        val controller = WifiRemoteController(backgroundScope, registry, { _, _ -> connection }, {}, {})
        controller.connect("camera", "127.0.0.1")
        runCurrent()
        controller.capture("camera")
        runCurrent()
        connection.result.complete(WifiCaptureStatus.Captured)
        runCurrent()
        assertEquals(WifiCaptureStatus.Captured, registry.get("camera")?.wifiRemote?.capture)
        connection.losses.send(Unit)
        runCurrent()
        assertEquals(WifiRemoteFailure.NetworkLost, registry.get("camera")?.wifiRemote?.failure)
        assertEquals(1, connection.captures)
        assertNull(controller.owner.value)
    }

    @Test
    fun cancellationWhileOpeningReleasesBleOwnership() = runTest {
        var released = false
        val controller = WifiRemoteController(backgroundScope, CameraSessionRegistry(), { _, _ -> awaitCancellation() },
            {}, { released = true })
        controller.connect("camera", "127.0.0.1")
        runCurrent()
        controller.closeAndJoin()
        assertTrue(released)
        assertNull(controller.owner.value)
    }

    @Test
    fun cancellationBeforeDispatchDoesNotLeaveBusyOwner() = runTest {
        val controller = WifiRemoteController(backgroundScope, CameraSessionRegistry(), { _, _ -> awaitCancellation() }, {}, {})
        controller.connect("camera", "127.0.0.1")
        controller.disconnect()
        runCurrent()
        assertNull(controller.owner.value)
    }

    @Test
    fun automaticApprovalUsesSessionStateAndExcludesAnotherAttempt() = runTest {
        val registry = CameraSessionRegistry()
        var attempts = 0
        var released = false
        val controller = WifiRemoteController(backgroundScope, registry, { _, _ -> error("Not manual") }, {},
            { released = true }, WifiAutomaticConnector { id, _, onPhase ->
                assertEquals("CAMERA", id)
                attempts++
                onPhase(WifiRemotePhase.AwaitingNetworkApproval)
                awaitCancellation()
            })
        controller.connectAutomatically("camera")
        runCurrent()
        assertEquals(WifiRemotePhase.AwaitingNetworkApproval, registry.get("camera")?.wifiRemote?.phase)
        controller.connectAutomatically("other")
        controller.connect("other", "127.0.0.1")
        controller.capture("camera")
        assertEquals(1, attempts)
        controller.closeAndJoin()
        assertTrue(released)
        assertNull(controller.owner.value)
    }

    @Test
    fun disconnectRequestsWifiOffAfterPreviewCleanupAndBeforeClosingSockets() = runTest {
        val registry = CameraSessionRegistry()
        val connection = Connection().apply { shutdownEnabled = true }
        val controller =
            WifiRemoteController(backgroundScope, registry, { _, _ -> connection }, {}, {})
        controller.connect("camera", "127.0.0.1")
        runCurrent()
        controller.closeAndJoin()
        assertEquals(listOf("preview", "wifi-off", "connection"), connection.cleanup)
        assertEquals(WifiShutdownStatus.Requested, registry.get("camera")?.wifiRemote?.wifiShutdown)
    }

    @Test
    fun connectionLossDoesNotAttemptWifiShutdown() = runTest {
        val connection = Connection().apply { shutdownEnabled = true }
        val controller = WifiRemoteController(
            backgroundScope,
            CameraSessionRegistry(),
            { _, _ -> connection },
            {},
            {})
        controller.connect("camera", "127.0.0.1")
        runCurrent()
        connection.losses.send(Unit)
        runCurrent()
        assertFalse("wifi-off" in connection.cleanup)
    }

    @Test
    fun browserPausesPreviewBlocksCaptureAndPreventsDuplicateDownloads() = runTest {
        val registry = CameraSessionRegistry()
        val connection = Connection()
        val controller =
            WifiRemoteController(backgroundScope, registry, { _, _ -> connection }, {}, {})
        controller.connect("camera", "127.0.0.1")
        runCurrent()
        controller.browsePhotos("camera")
        controller.capture("camera")
        runCurrent()
        assertEquals(listOf("preview", "browse"), connection.cleanup)
        assertEquals(0, connection.captures)
        controller.downloadPhoto("camera", 42L)
        controller.downloadPhoto("camera", 42L)
        runCurrent()
        assertEquals(1, connection.downloads)
        connection.downloadResult.complete(
            WifiImageTransferState(
                WifiImageTransferStatus.Saved,
                "DSC.JPG"
            )
        )
        runCurrent()
        controller.downloadPhoto("camera", 42L)
        runCurrent()
        assertEquals(1, connection.downloads)
        assertEquals(setOf(42L), registry.get("camera")?.wifiRemote?.photoBrowser?.savedHandles)
        controller.leavePhotoBrowser("camera")
        runCurrent()
        assertFalse(registry.get("camera")!!.wifiRemote.photoBrowser.open)
        controller.closeAndJoin()
        assertEquals(
            listOf("browse-close", "preview", "connection"),
            connection.cleanup.takeLast(3)
        )
    }

    @Test
    fun pairedCapturesRequestOnlyRawPreviewsAndDownloadEachSelectedFormatIndependently() = runTest {
        val registry = CameraSessionRegistry()
        val connection = Connection().apply {
            photos = listOf(
                WifiCameraPhoto(42, "ONE.HIF", 100, "image/heif", "", captureId = "one"),
                WifiCameraPhoto(43, "ONE.ARW", 100, "image/x-sony-arw", "", captureId = "one"),
                WifiCameraPhoto(44, "TWO.JPG", 100, "image/jpeg", "", captureId = "two"),
                WifiCameraPhoto(45, "TWO.ARW", 100, "image/x-sony-arw", "", captureId = "two"),
                WifiCameraPhoto(46, "THREE.HIF", 100, "image/heif", "", captureId = "three"),
            )
        }
        val controller = WifiRemoteController(backgroundScope, registry, { _, _ -> connection }, {}, {})
        controller.connect("camera", "127.0.0.1")
        runCurrent()
        controller.browsePhotos("camera")
        runCurrent()
        assertEquals(listOf(43L, 45L, 46L), connection.thumbnailRequests)
        connection.downloadResult.complete(WifiImageTransferState(WifiImageTransferStatus.Saved))
        controller.downloadPhoto("camera", 42)
        runCurrent()
        assertEquals(setOf(42L), registry.get("camera")!!.wifiRemote.photoBrowser.savedHandles)
        controller.downloadPhoto("camera", 43)
        runCurrent()
        assertEquals(listOf(42L, 43L), connection.downloadedHandles)
        assertEquals(setOf(42L, 43L), registry.get("camera")!!.wifiRemote.photoBrowser.savedHandles)
        controller.closeAndJoin()
    }

    @Test
    fun disconnectAbortsDownloadBeforeLeavingBrowseModeAndTurningOffWifi() = runTest {
        val connection = Connection().apply { shutdownEnabled = true }
        val controller = WifiRemoteController(
            backgroundScope,
            CameraSessionRegistry(),
            { _, _ -> connection },
            {},
            {})
        controller.connect("camera", "127.0.0.1")
        runCurrent()
        controller.browsePhotos("camera")
        runCurrent()
        controller.downloadPhoto("camera", 42L)
        runCurrent()
        controller.closeAndJoin()
        assertEquals(
            listOf("download", "browse-close", "wifi-off", "connection"),
            connection.cleanup.takeLast(4)
        )
        assertTrue(controller.thumbnails.value.isEmpty())
    }

    @Test
    fun cancelledDownloadKeepsConnectionAndFailedModeExitDoesNotRestartPreview() = runTest {
        val registry = CameraSessionRegistry()
        val connection = Connection()
        val controller =
            WifiRemoteController(backgroundScope, registry, { _, _ -> connection }, {}, {})
        controller.connect("camera", "127.0.0.1")
        runCurrent()
        controller.browsePhotos("camera")
        runCurrent()
        controller.downloadPhoto("camera", 42L)
        runCurrent()
        controller.cancelPhotoOperation("camera")
        runCurrent()
        assertEquals(
            WifiImageTransferStatus.Cancelled,
            registry.get("camera")?.wifiRemote?.imageTransfer?.status
        )
        assertEquals(WifiRemotePhase.Ready, registry.get("camera")?.wifiRemote?.phase)
        connection.failBrowserExit = true
        controller.leavePhotoBrowser("camera")
        runCurrent()
        assertTrue(registry.get("camera")!!.wifiRemote.photoBrowser.open)
        assertTrue(registry.get("camera")!!.wifiRemote.photoBrowser.failed)
        controller.capture("camera")
        assertEquals(0, connection.captures)
        connection.failBrowserExit = false
        controller.closeAndJoin()
        assertEquals(1, connection.cleanup.count { it == "preview" })
    }

}
