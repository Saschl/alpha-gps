package com.sasch.cameragps.sharednew

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionRegistry
import com.sasch.cameragps.sharednew.bluetooth.toByteArray
import com.sasch.cameragps.sharednew.remote.wifi.WifiCaptureStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteConnection
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteController
import com.sasch.cameragps.sharednew.ui.remote.WifiRemoteScreen
import com.sasch.cameragps.sharednew.ui.remote.WifiRemoteViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.jetbrains.compose.resources.decodeToImageBitmap
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

// The capture tool injects this photo into the built simulator app only.
@Composable
internal fun StoreWifiScreenshot() {
    val scope = rememberCoroutineScope()
    val model = remember {
        val path = requireNotNull(NSBundle.mainBundle.pathForResource("wifi-preview", "jpg"))
        val image = requireNotNull(NSData.dataWithContentsOfFile(path)).toByteArray().decodeToImageBitmap()
        val controller = WifiRemoteController(
            scope, CameraSessionRegistry(), { _, _ -> StoreWifiConnection(image) }, {}, {},
        )
        WifiRemoteViewModel("SCREENSHOT-CAMERA", controller)
    }
    DisposableEffect(model) {
        model.controller.connect(model.identifier, "127.0.0.1")
        onDispose { model.controller.disconnect(model.identifier) }
    }
    WifiRemoteScreen(
        viewModel = model,
        onConnect = {},
        onWifiSettings = {},
        onClose = {},
        isExperimentalNoticeAcknowledged = { true },
        onAcknowledgeExperimentalNotice = {},
    )
}

internal class StoreWifiConnection(image: ImageBitmap) : WifiRemoteConnection {
    override val cameraName = "ILCE-7M4"
    override val canCapture = true
    override val canTransferImages = true
    override val images = flow { emit(image); awaitCancellation() }
    override val lost = flow<Unit> { awaitCancellation() }
    override suspend fun capture() = WifiCaptureStatus.Captured
    override suspend fun close() {}
}
