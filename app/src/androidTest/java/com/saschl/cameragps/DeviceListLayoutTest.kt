package com.saschl.cameragps

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.add_camera
import cameragps.sharednew.generated.resources.device_list_need_help
import cameragps.sharednew.generated.resources.guide_open_button
import cameragps.sharednew.generated.resources.location_linking_disabled_by_camera
import cameragps.sharednew.generated.resources.trigger_shutter
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.ui.devicelist.AddCameraButton
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListItem
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListLayout
import com.sasch.cameragps.sharednew.ui.devicelist.EmptyStateCard
import com.sasch.cameragps.sharednew.ui.devicelist.SharedDeviceList
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeviceListLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyListKeepsAddCameraAndHelpAvailable() {
        var additions = 0
        var helpRequests = 0
        lateinit var addLabel: String
        lateinit var helpLabel: String
        compose.setContent {
            MaterialTheme {
                addLabel = stringResource(Res.string.add_camera)
                helpLabel = stringResource(Res.string.device_list_need_help)
                DeviceListLayout(
                    onNeedHelp = { helpRequests++ },
                    header = { AddCameraButton { additions++ } },
                ) {
                    EmptyStateCard(title = "No cameras", message = "Pair a camera to begin.")
                }
            }
        }

        compose.onNodeWithText("No cameras").assertIsDisplayed()
        compose.onNodeWithText(addLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText(helpLabel).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, additions)
            assertEquals(1, helpRequests)
        }
    }

    @Test
    fun largeTextAndLongListKeepFooterVisibleAndLastCameraReachable() {
        lateinit var helpLabel: String
        val devices = (1..30).map {
            BluetoothDeviceInfo("CAMERA-$it", "Camera $it", isConnected = false, isSaved = true)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density.density,
                    fontScale = 2f
                )
            ) {
                MaterialTheme {
                    helpLabel = stringResource(Res.string.device_list_need_help)
                    DeviceListLayout(
                        onNeedHelp = {},
                        header = { AddCameraButton {} },
                    ) {
                        SharedDeviceList(
                            devices = devices,
                            items = emptyMap(),
                            onConnect = {},
                            onTriggerRemoteShutter = {},
                            onDelete = {},
                            onOpenDetails = {},
                            onOpenTroubleshooting = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(helpLabel).assertIsDisplayed()
        // One section header followed by 30 saved cameras.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        compose.onNodeWithText("Camera 30").assertIsDisplayed()
        compose.onNodeWithText(helpLabel).assertIsDisplayed()
    }

    @Test
    fun savedCameraAndShutterKeepSeparateActionsInsideSharedLayout() {
        val device = BluetoothDeviceInfo("CAMERA", "My Sony", isConnected = true, isSaved = true)
        var details: BluetoothDeviceInfo? = null
        var shutter: BluetoothDeviceInfo? = null
        var connections = 0
        lateinit var shutterLabel: String
        compose.setContent {
            MaterialTheme {
                shutterLabel = stringResource(Res.string.trigger_shutter)
                DeviceListLayout(onNeedHelp = {}) {
                    SharedDeviceList(
                        devices = listOf(device),
                        items = mapOf(
                            device.identifier to DeviceListItem(
                                identifier = device.identifier,
                                customName = null,
                                isAlwaysOnEnabled = true,
                                isTransmissionActive = true,
                                isRemoteFeatureActive = true,
                                isShutterActive = false,
                            )
                        ),
                        hapticsEnabled = false,
                        onConnect = { connections++ },
                        onTriggerRemoteShutter = { shutter = it },
                        onDelete = {},
                        onOpenDetails = { details = it },
                        onOpenTroubleshooting = {},
                    )
                }
            }
        }

        compose.onNodeWithText(shutterLabel).performClick()
        compose.runOnIdle {
            assertEquals(device, shutter)
            assertEquals(null, details)
        }
        compose.onNodeWithText(device.name).performClick()
        compose.runOnIdle {
            assertEquals(device, details)
            assertEquals(0, connections)
        }
    }

    @Test
    fun cameraLocationWarningOffersHelpWithoutOpeningDetailsAndClearsWhenResolved() {
        val device = BluetoothDeviceInfo("CAMERA", "My Sony", isConnected = true, isSaved = true)
        val locationDisabled = mutableStateOf(true)
        var helpRequests = 0
        var detailsRequests = 0
        lateinit var helpLabel: String
        lateinit var warning: String
        compose.setContent {
            MaterialTheme {
                helpLabel = stringResource(Res.string.guide_open_button)
                warning = stringResource(Res.string.location_linking_disabled_by_camera)
                DeviceListLayout(onNeedHelp = {}) {
                    SharedDeviceList(
                        devices = listOf(device),
                        items = mapOf(
                            device.identifier to DeviceListItem(
                                identifier = device.identifier,
                                customName = null,
                                isAlwaysOnEnabled = true,
                                isTransmissionActive = false,
                                isRemoteFeatureActive = false,
                                isShutterActive = false,
                                locationDisabledByCamera = locationDisabled.value,
                            )
                        ),
                        hapticsEnabled = false,
                        onConnect = {},
                        onTriggerRemoteShutter = {},
                        onDelete = {},
                        onOpenDetails = { detailsRequests++ },
                        onOpenTroubleshooting = { helpRequests++ },
                    )
                }
            }
        }

        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.onNodeWithText(helpLabel).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, helpRequests)
            assertEquals(0, detailsRequests)
            locationDisabled.value = false
        }
        compose.onNodeWithText(warning).assertDoesNotExist()
        compose.onNodeWithText(helpLabel).assertDoesNotExist()
        compose.onNodeWithText(device.name).performClick()
        compose.runOnIdle { assertEquals(1, detailsRequests) }
    }
}
