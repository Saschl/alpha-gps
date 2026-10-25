package com.saschl.cameragps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.add_camera
import cameragps.sharednew.generated.resources.device_list_need_help
import cameragps.sharednew.generated.resources.pairing_preparation_search
import cameragps.sharednew.generated.resources.pairing_preparation_step_ready
import cameragps.sharednew.generated.resources.trigger_shutter
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.ui.device.SharedDevicesScreen
import com.sasch.cameragps.sharednew.ui.devicelist.AddCameraButton
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListItem
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListLayout
import com.sasch.cameragps.sharednew.ui.devicelist.SharedDeviceList
import com.sasch.cameragps.sharednew.ui.pairing.SharedPairingPreparationScreen
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsColumn
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsScreen
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class AdaptiveLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun resizingCameraListBoundsContentAndActionsAndKeepsScrollPosition() {
        val width = mutableStateOf(320.dp)
        val devices = (1..30).map {
            BluetoothDeviceInfo("CAMERA-$it", "Camera $it", isSaved = true, isConnected = it == 1)
        }
        lateinit var add: String
        lateinit var help: String
        lateinit var shutter: String
        var shutterPresses = 0
        compose.setContent {
            TestViewport(width.value) {
                add = stringResource(Res.string.add_camera)
                help = stringResource(Res.string.device_list_need_help)
                shutter = stringResource(Res.string.trigger_shutter)
                SharedDevicesScreen(title = "Cameras", topBarActions = {}) {
                    DeviceListLayout(onNeedHelp = {}, header = { AddCameraButton {} }) {
                        SharedDeviceList(
                            devices = devices,
                            items = mapOf(
                                "CAMERA-1" to DeviceListItem(
                                    identifier = "CAMERA-1",
                                    customName = null,
                                    isAlwaysOnEnabled = true,
                                    isTransmissionActive = true,
                                    isRemoteFeatureActive = true,
                                    isShutterActive = false,
                                )
                            ),
                            hapticsEnabled = false,
                            onConnect = {},
                            onDelete = {},
                            onOpenDetails = {},
                            onOpenTroubleshooting = {},
                            onTriggerRemoteShutter = { shutterPresses++ },
                        )
                    }
                }
            }
        }
        val narrow = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        assertTrue(
            compose.onNodeWithText(add).getUnclippedBoundsInRoot().width > narrow.width * 0.8f
        )
        compose.runOnIdle { width.value = 1024.dp }
        val wide = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        val list = compose.onNode(hasScrollToIndexAction()).getUnclippedBoundsInRoot()
        assertTrue(list.width < wide.width * 0.72f)
        assertTrue(abs((list.left - wide.left).value - (wide.right - list.right).value) < wide.width.value * 0.02f)
        assertTrue(compose.onNodeWithText(add).getUnclippedBoundsInRoot().width < wide.width / 2)
        assertTrue(
            compose.onNodeWithText(shutter).getUnclippedBoundsInRoot().width < wide.width / 2
        )
        compose.onNodeWithText(shutter).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, shutterPresses) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        compose.onNodeWithText("Camera 30").assertIsDisplayed()
        compose.runOnIdle { width.value = 320.dp }
        compose.onNodeWithText("Camera 30").assertIsDisplayed()
        compose.onNodeWithText(add).assertIsDisplayed()
        compose.onNodeWithText(help).assertIsDisplayed()
    }

    @Test
    fun longCameraNameAndShutterLabelWrapWithLargeText() {
        val name = "Sony camera with a long custom name for landscape photography"
        lateinit var shutter: String
        compose.setContent {
            TestViewport(320.dp, fontScale = 2f) {
                shutter = stringResource(Res.string.trigger_shutter)
                SharedDeviceList(
                    devices = listOf(
                        BluetoothDeviceInfo(
                            "CAMERA",
                            name,
                            isSaved = true,
                            isConnected = true
                        )
                    ),
                    items = mapOf(
                        "CAMERA" to DeviceListItem(
                            identifier = "CAMERA",
                            customName = null,
                            isAlwaysOnEnabled = true,
                            isTransmissionActive = true,
                            isRemoteFeatureActive = true,
                            isShutterActive = false,
                        )
                    ),
                    hapticsEnabled = false,
                    onConnect = {},
                    onDelete = {},
                    onOpenDetails = {},
                    onOpenTroubleshooting = {},
                    onTriggerRemoteShutter = {},
                )
            }
        }
        for (text in listOf(name, shutter)) {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue(results.isNotEmpty())
            assertFalse(
                "Text must wrap without clipping: $text",
                results.single().hasVisualOverflow
            )
        }
        compose.onNodeWithText(shutter).assertIsDisplayed()
    }

    @Test
    fun settingsStayCenteredAndScrollableAcrossWindowSizes() {
        val width = mutableStateOf(1024.dp)
        compose.setContent {
            TestViewport(width.value) {
                SharedSettingsScreen("Settings", {}, {}, {}) { padding ->
                    SharedSettingsColumn(Modifier
                        .fillMaxSize()
                        .padding(padding)) {
                        repeat(20) { index ->
                            Box(Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .testTag("setting-$index")) {
                                Text("Setting $index")
                            }
                        }
                    }
                }
            }
        }
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("setting-0").getUnclippedBoundsInRoot()
        assertTrue(card.width < viewport.width * 0.72f)
        assertTrue(abs((card.left - viewport.left).value - (viewport.right - card.right).value) < 1f)
        compose.onNodeWithText("Setting 19").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { width.value = 320.dp }
        compose.onNodeWithText("Setting 19").assertIsDisplayed()
        val narrow = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        assertTrue(
            compose.onNodeWithTag("setting-19")
                .getUnclippedBoundsInRoot().width > narrow.width * 0.8f
        )
    }

    @Test
    fun pairingInstructionsAndSearchRemainUsableOnWideAndNarrowWindows() {
        val width = mutableStateOf(1024.dp)
        lateinit var search: String
        lateinit var instruction: String
        var searches = 0
        compose.setContent {
            TestViewport(width.value, fontScale = 1.5f) {
                search = stringResource(Res.string.pairing_preparation_search)
                instruction = stringResource(Res.string.pairing_preparation_step_ready)
                SharedPairingPreparationScreen(false, { searches++ }, {}, {})
            }
        }
        val wide = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        assertTrue(compose.onNodeWithText(search).getUnclippedBoundsInRoot().width < wide.width / 2)
        compose.onNodeWithText(search).assertIsDisplayed().performClick()
        compose.runOnIdle { width.value = 320.dp }
        compose.onNodeWithText(instruction).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(search).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, searches) }
    }
}

@Composable
private fun TestViewport(width: Dp, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val window = LocalWindowInfo.current.containerSize
    // Fit tablet-sized test windows even when this suite runs on a phone emulator.
    val density = minOf(LocalDensity.current.density, window.width / 1200f, window.height / 900f)
    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
        MaterialTheme {
            Box(Modifier
                .size(width, 700.dp)
                .testTag("viewport")) {
                content()
            }
        }
    }
}

private val DpRect.width: Dp get() = right - left
