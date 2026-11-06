package com.sasch.cameragps.sharednew

import androidx.compose.ui.graphics.ImageBitmap
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionRegistry
import com.sasch.cameragps.sharednew.remote.wifi.WifiPreviewStatus
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteController
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemotePhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreWifiScreenshotTest {
    @Test
    fun sampleConnectionKeepsLiveViewReadyWithoutNetwork() = runTest {
        val image = ImageBitmap(2, 2)
        val registry = CameraSessionRegistry()
        val controller = WifiRemoteController(
            backgroundScope, registry, { _, _ -> StoreWifiConnection(image) }, {}, {},
        )
        controller.connect("screenshot-camera", "127.0.0.1")
        runCurrent()
        val state = requireNotNull(registry.get("SCREENSHOT-CAMERA")).wifiRemote
        assertEquals(WifiRemotePhase.Ready, state.phase)
        assertEquals(WifiPreviewStatus.Streaming, state.preview)
        assertTrue(state.hasLiveView && state.canCaptureStill)
        assertSame(image, controller.image.value)
        controller.closeAndJoin()
    }
}
