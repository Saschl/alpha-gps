package com.sasch.cameragps.sharednew

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.baseline_view_list_24
import cameragps.sharednew.generated.resources.header_device_list
import cameragps.sharednew.generated.resources.info_24px
import cameragps.sharednew.generated.resources.settings
import cameragps.sharednew.generated.resources.settings_24px
import cameragps.sharednew.generated.resources.view_logs
import cameragps.sharednew.generated.resources.welcome_get_started_button
import cameragps.sharednew.generated.resources.welcome_settings_note
import cameragps.sharednew.generated.resources.welcome_subtitle
import cameragps.sharednew.generated.resources.welcome_title
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.VariableLogLevel
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.database.logging.DatabaseLogger
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import com.sasch.cameragps.sharednew.ui.device.SharedDevicesScreen
import com.sasch.cameragps.sharednew.ui.logs.SharedLogViewerScreen
import com.sasch.cameragps.sharednew.ui.welcome.SharedWelcomeScreen
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal enum class IosScreen {
    Welcome,
    Devices,
    Settings,
    Help,
    Logs,
}

@Composable
internal fun CameraGpsIosApp() {
    val bluetoothController = IosBluetoothController
    val realDevices by bluetoothController.devices.collectAsState()
    val devices = if (SCREENSHOT_MODE) mockDevices else realDevices
    val scope = rememberCoroutineScope()
    val logRepository = remember { LogRepository(getDatabaseBuilder()) }
    var currentScreen by remember {
        mutableStateOf(
            if (IosAppPreferences.showWelcomeOnLaunch()) IosScreen.Welcome else IosScreen.Devices
        )
    }
    var isAppEnabled by remember { mutableStateOf(IosAppPreferences.isAppEnabled()) }
    var autoScanEnabled by remember { mutableStateOf(IosAppPreferences.isAutoScanEnabled()) }

    LaunchedEffect(Unit) {
        KmLogging.setLoggers(
            DatabaseLogger(
                logRepository,
                VariableLogLevel(LogLevel.valueOf(IosAppPreferences.getLogLevel()))
            )
        )
    }
    LaunchedEffect(currentScreen, isAppEnabled, autoScanEnabled) {
        if (SCREENSHOT_MODE) return@LaunchedEffect
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
                secondStepFeatures = emptyList(),
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
                title = stringResource(Res.string.header_device_list),
                topBarActions = {
                    IconButton(onClick = { currentScreen = IosScreen.Help }) {
                        Icon(
                            painterResource(Res.drawable.info_24px),
                            contentDescription = stringResource(Res.string.settings)
                        )
                    }
                    IconButton(onClick = { currentScreen = IosScreen.Logs }) {
                        Icon(
                            painterResource(Res.drawable.baseline_view_list_24),
                            contentDescription = stringResource(Res.string.view_logs)
                        )
                    }
                    IconButton(onClick = { currentScreen = IosScreen.Settings }) {
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
                onOpenHelp = { currentScreen = IosScreen.Help },
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
                onChangeLogLevel = { level ->
                    KmLogging.setLoggers(DatabaseLogger(logRepository, VariableLogLevel(level)))
                }
            )
        }

        IosScreen.Help -> {
            IosHelpScreen(
                onBackClick = { currentScreen = IosScreen.Devices }
            )
        }

        IosScreen.Logs -> {
            SharedLogViewerScreen(
                logRepository = logRepository,
                onBackClick = { currentScreen = IosScreen.Devices }
            )
        }
    }
}


