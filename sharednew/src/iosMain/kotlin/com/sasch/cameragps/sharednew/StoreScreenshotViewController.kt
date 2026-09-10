package com.sasch.cameragps.sharednew

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.window.ComposeUIViewController
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.baseline_view_list_24
import cameragps.sharednew.generated.resources.header_device_list
import cameragps.sharednew.generated.resources.info_24px
import cameragps.sharednew.generated.resources.settings_24px
import com.sasch.cameragps.sharednew.ui.device.SharedDevicesScreen
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListItem
import com.sasch.cameragps.sharednew.ui.pairing.SharedPairingPreparationScreen
import com.sasch.cameragps.sharednew.ui.theme.CameraGpsTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import platform.UIKit.UIViewController

/**
 * Real production composables with deterministic sample data and inert callbacks.
 * The Swift host exposes this entry point only in Debug simulator builds. It never
 * constructs the Bluetooth controller, requests permissions or starts diagnostics.
 */
@Suppress("FunctionName", "unused")
fun StoreScreenshotViewController(scenario: String): UIViewController {
    require(scenario in setOf("geotagging", "reconnect", "remote", "multiple", "privacy", "pairing"))
    return ComposeUIViewController {
        CameraGpsTheme(darkTheme = false) {
            if (scenario == "pairing") {
                SharedPairingPreparationScreen(
                    isSearching = false,
                    onSearch = {},
                    onBack = {},
                    onHelp = {},
                )
            } else if (scenario == "privacy") {
                IosSettingsScreen(
                    isAppEnabled = true,
                    autoScanEnabled = false,
                    hapticsEnabled = true,
                    transmissionNotificationsEnabled = true,
                    transmissionNotificationsPermissionDenied = false,
                    onTransmissionNotificationsEnabledChange = {},
                    onOpenNotificationSettings = {},
                    onBackClick = {},
                    onOpenHelp = {},
                    onAppEnabledChange = {},
                    onAutoScanEnabledChange = {},
                    onHapticsEnabledChange = {},
                    sentryEnabled = false,
                    onSentryEnabledChange = {},
                    onShowWelcomeAgain = {},
                    onChangeLogLevel = {},
                )
            } else {
                // Share the long fixture with SCREENSHOT_MODE so both entry points
                // exercise scrolling; remote keeps its single-camera composition.
                val sampleDevices = if (scenario == "remote") mockDevices.take(1) else mockDevices
                val devices = sampleDevices.mapIndexed { index, device ->
                    device.copy(
                        isSaved = true,
                        isConnected = scenario != "reconnect" || index == 0,
                    )
                }
                val items = devices.associate { device ->
                    device.identifier to DeviceListItem(
                        identifier = device.identifier,
                        customName = null,
                        isAlwaysOnEnabled = false,
                        isTransmissionActive = device.isConnected,
                        isRemoteFeatureActive = device.isConnected,
                        isShutterActive = false,
                    )
                }
                SharedDevicesScreen(
                    title = stringResource(Res.string.header_device_list),
                    topBarActions = {
                        for (icon in listOf(Res.drawable.info_24px, Res.drawable.baseline_view_list_24, Res.drawable.settings_24px)) {
                            IconButton(onClick = {}) {
                                Icon(painterResource(icon), contentDescription = null)
                            }
                        }
                    },
                ) {
                    DeviceListContent(
                        devices = devices,
                        items = items,
                        isAppEnabled = true,
                        hapticsEnabled = false,
                        migrationCandidates = emptyList(),
                        onMigrate = {},
                        onAddCamera = {},
                        onOpenSettings = {},
                        onOpenHelp = {},
                        onConnect = {},
                        onTriggerRemoteShutter = {},
                        onDelete = {},
                        onOpenDetails = {},
                    )
                }
            }
        }
    }
}
