package com.sasch.cameragps.sharednew

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_name_ui
import cameragps.sharednew.generated.resources.settings
import cameragps.sharednew.generated.resources.settings_24px
import cameragps.sharednew.generated.resources.welcome_get_started_button
import cameragps.sharednew.generated.resources.welcome_settings_note
import cameragps.sharednew.generated.resources.welcome_subtitle
import cameragps.sharednew.generated.resources.welcome_title
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.ui.device.SharedDevicesScreen
import com.sasch.cameragps.sharednew.ui.welcome.SharedWelcomeScreen
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal enum class IosScreen {
    Welcome,
    Devices,
    Settings,
}

@Composable
internal fun CameraGpsIosApp() {
    val bluetoothController = IosBluetoothController
    val devices by bluetoothController.devices.collectAsState()
    val scope = rememberCoroutineScope()
    var currentScreen by remember {
        mutableStateOf(
            if (IosAppPreferences.showWelcomeOnLaunch()) IosScreen.Welcome else IosScreen.Devices
        )
    }
    var isAppEnabled by remember { mutableStateOf(IosAppPreferences.isAppEnabled()) }
    var autoScanEnabled by remember { mutableStateOf(IosAppPreferences.isAutoScanEnabled()) }

    LaunchedEffect(currentScreen, isAppEnabled, autoScanEnabled) {
        if (currentScreen == IosScreen.Devices && isAppEnabled && autoScanEnabled) {
            bluetoothController.startScan()
        } else {
            bluetoothController.stopScan()
        }
    }

    when (currentScreen) {
        IosScreen.Welcome -> {
            SharedWelcomeScreen(
                title = stringResource(Res.string.welcome_title),
                subtitle = stringResource(Res.string.welcome_subtitle),
                getStartedText = stringResource(Res.string.welcome_get_started_button),
                settingsNote = stringResource(Res.string.welcome_settings_note),
                firstStepFeatures = firstStepFeatures(),
                secondStepFeatures = secondStepFeatures(),
                onGetStarted = {
                    IosAppPreferences.setShowWelcomeOnLaunch(false)
                    currentScreen = IosScreen.Devices
                },
                iconContent = {
                    Text(
                        text = "📷",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
            )
        }

        IosScreen.Devices -> {
            SharedDevicesScreen(
                title = stringResource(Res.string.app_name_ui),
                topBarActions = {
                    TextButton(onClick = { currentScreen = IosScreen.Settings }) {
                        Icon(
                            painterResource(Res.drawable.settings_24px),
                            contentDescription = stringResource(Res.string.settings)
                        )
                    }
                },
            ) {
                DeviceListContent(
                    devices = devices,
                    isAppEnabled = isAppEnabled,
                    isScanning = autoScanEnabled,
                    onOpenSettings = { currentScreen = IosScreen.Settings },
                    onConnect = { device ->
                        scope.launch {
                            if (device.isConnected) {
                                bluetoothController.disconnect(device.identifier)
                            } else {
                                bluetoothController.connect(device.identifier)
                            }
                        }
                    },
                    onDelete = { device ->
                        scope.launch {
                            bluetoothController.forgetDevice(device.identifier)
                        }
                    },
                )
            }
        }

        IosScreen.Settings -> {
            IosSettingsScreen(
                isAppEnabled = isAppEnabled,
                autoScanEnabled = autoScanEnabled,
                onBackClick = { currentScreen = IosScreen.Devices },
                onAppEnabledChange = { enabled ->
                    isAppEnabled = enabled
                    IosAppPreferences.setAppEnabled(enabled)
                },
                onAutoScanEnabledChange = { enabled ->
                    autoScanEnabled = enabled
                    IosAppPreferences.setAutoScanEnabled(enabled)
                },
                onShowWelcomeAgain = {
                    IosAppPreferences.setShowWelcomeOnLaunch(true)
                    currentScreen = IosScreen.Welcome
                },
            )
        }
    }
}


