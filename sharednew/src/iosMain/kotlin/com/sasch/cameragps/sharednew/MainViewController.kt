package com.sasch.cameragps.sharednew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.ui.device.SharedDevicesScreen
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsCard
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsScreen
import com.sasch.cameragps.sharednew.ui.settings.SharedToggleRow
import com.sasch.cameragps.sharednew.ui.welcome.SharedWelcomeScreen
import com.sasch.cameragps.sharednew.ui.welcome.WelcomeFeature
import kotlinx.coroutines.launch
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

/**
 * Entry point for the iOS host application.
 *
 * The Xcode project should call:
 *   CameraGpsShared.MainViewControllerKt.MainViewController()
 * and embed the returned UIViewController as the root view controller.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController =
    ComposeUIViewController {
        //MaterialTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CameraGpsIosApp()

        }
        //}
}

private enum class IosScreen {
    Welcome,
    Devices,
    Settings,
}

private object IosUiCopy {
    const val appName = "Alpha GPS"
    const val settings = "Settings"
    const val welcomeTitle = "Welcome to Alpha GPS"
    const val welcomeSubtitle = "Real-time GPS coordinates for your camera via Bluetooth"
    const val getStarted = "Get Started"
    const val settingsNote = "You can access these settings later from the main menu"
    const val appControls = "App Controls"
    const val enableApp = "Enable App"
    const val enableAppDescription = "Allow the app to run and discover nearby cameras"
    const val autoScan = "Scan on screen open"
    const val autoScanDescription =
        "Automatically look for nearby cameras when the devices screen is visible"
    const val showWelcomeAgain = "Show welcome screen on next launch"
    const val showWelcomeAgainDescription =
        "Reopen the onboarding flow the next time the app starts"
    const val help = "Help"
    const val helpText =
        "The iOS build now uses the same shared welcome, devices, and settings building blocks as Android."
    const val documentation = "Documentation"
    const val documentationText =
        "See the repository README and privacy documentation for setup, permissions, and troubleshooting."
    const val disabledTitle = "App is disabled"
    const val disabledMessage = "Enable the app in Settings to scan for nearby cameras."
    const val pausedTitle = "Scanning is paused"
    const val pausedMessage = "Turn on automatic scanning in Settings to look for nearby cameras."
    const val scanningText = "Scanning for cameras…"
    const val noDevicesMessage =
        "Make sure your camera is powered on and in Bluetooth pairing or discovery mode."
    const val openSettings = "Open Settings"
    const val back = "Back"

    val firstStepFeatures = listOf(
        WelcomeFeature(
            title = "Connect Your Camera",
            description = "Pair your Sony camera with your phone via Bluetooth for seamless GPS data transfer",
        ),
        WelcomeFeature(
            title = "Real-time GPS Sync",
            description = "Automatically sync your phone's GPS coordinates to your camera for accurate geotagging",
        ),
    )

    val secondStepFeatures = listOf(
        WelcomeFeature(
            title = "Quickstart",
            description = "On the next screen, start scanning and tap a nearby camera to connect.",
        ),
        WelcomeFeature(
            title = "Always On mode",
            description = "Android offers an additional Always On mode. On iOS, keeping Bluetooth enabled and scanning active provides the closest workflow.",
        ),
    )
}

private object IosAppPreferences {
    private const val keyShowWelcome = "ios.showWelcome"
    private const val keyAppEnabled = "ios.appEnabled"
    private const val keyAutoScanEnabled = "ios.autoScanEnabled"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    fun showWelcomeOnLaunch(): Boolean = defaults.objectForKey(keyShowWelcome)?.let {
        defaults.boolForKey(keyShowWelcome)
    } ?: true

    fun setShowWelcomeOnLaunch(show: Boolean) {
        defaults.setBool(show, forKey = keyShowWelcome)
    }

    fun isAppEnabled(): Boolean = defaults.objectForKey(keyAppEnabled)?.let {
        defaults.boolForKey(keyAppEnabled)
    } ?: true

    fun setAppEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = keyAppEnabled)
    }

    fun isAutoScanEnabled(): Boolean = defaults.objectForKey(keyAutoScanEnabled)?.let {
        defaults.boolForKey(keyAutoScanEnabled)
    } ?: true

    fun setAutoScanEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = keyAutoScanEnabled)
    }
}

@Composable
private fun CameraGpsIosApp() {
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
                title = IosUiCopy.welcomeTitle,
                subtitle = IosUiCopy.welcomeSubtitle,
                getStartedText = IosUiCopy.getStarted,
                settingsNote = IosUiCopy.settingsNote,
                firstStepFeatures = IosUiCopy.firstStepFeatures,
                secondStepFeatures = IosUiCopy.secondStepFeatures,
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
                title = IosUiCopy.appName,
                topBarActions = {
                    TextButton(onClick = { currentScreen = IosScreen.Settings }) {
                        Text(IosUiCopy.settings)
                    }
                },
            ) { innerPadding ->
                DeviceListContent(
                    devices = devices,
                    innerPadding = innerPadding,
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

@Composable
private fun IosSettingsScreen(
    isAppEnabled: Boolean,
    autoScanEnabled: Boolean,
    onBackClick: () -> Unit,
    onAppEnabledChange: (Boolean) -> Unit,
    onAutoScanEnabledChange: (Boolean) -> Unit,
    onShowWelcomeAgain: () -> Unit,
) {
    SharedSettingsScreen(
        title = IosUiCopy.settings,
        onBackClick = onBackClick,
        onTitleClick = {},
        navigationIcon = { Text(IosUiCopy.back) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                SharedSettingsCard(title = IosUiCopy.appControls) {
                    SharedToggleRow(
                        title = IosUiCopy.enableApp,
                        description = IosUiCopy.enableAppDescription,
                        checked = isAppEnabled,
                        onCheckedChange = onAppEnabledChange,
                    )
                    SharedToggleRow(
                        title = IosUiCopy.autoScan,
                        description = IosUiCopy.autoScanDescription,
                        checked = autoScanEnabled,
                        onCheckedChange = onAutoScanEnabledChange,
                    )
                    SharedToggleRow(
                        title = IosUiCopy.showWelcomeAgain,
                        description = IosUiCopy.showWelcomeAgainDescription,
                        checked = false,
                        onCheckedChange = { if (it) onShowWelcomeAgain() },
                        trailing = {
                            TextButton(onClick = onShowWelcomeAgain) {
                                Text(IosUiCopy.getStarted)
                            }
                        },
                    )
                }
            }
            item {
                SharedSettingsCard(title = IosUiCopy.help) {
                    Text(
                        text = IosUiCopy.helpText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SharedSettingsCard(title = IosUiCopy.documentation) {
                    Text(
                        text = IosUiCopy.documentationText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceListContent(
    devices: List<BluetoothDeviceInfo>,
    innerPadding: PaddingValues,
    isAppEnabled: Boolean,
    isScanning: Boolean,
    onOpenSettings: () -> Unit,
    onConnect: (BluetoothDeviceInfo) -> Unit,
) {
    when {
        !isAppEnabled -> {
            EmptyStateCard(
                innerPadding = innerPadding,
                title = IosUiCopy.disabledTitle,
                message = IosUiCopy.disabledMessage,
                actionLabel = IosUiCopy.openSettings,
                onAction = onOpenSettings,
            )
        }

        devices.isEmpty() && !isScanning -> {
            EmptyStateCard(
                innerPadding = innerPadding,
                title = IosUiCopy.pausedTitle,
                message = IosUiCopy.pausedMessage,
                actionLabel = IosUiCopy.openSettings,
                onAction = onOpenSettings,
            )
        }

        devices.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = IosUiCopy.scanningText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = IosUiCopy.noDevicesMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                items(devices, key = { it.identifier }) { device ->
                    DeviceCard(device = device, onConnect = { onConnect(device) })
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    innerPadding: PaddingValues,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: BluetoothDeviceInfo,
    onConnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onConnect,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = device.identifier,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (device.isConnected) "Connected" else "Tap to connect",
                style = MaterialTheme.typography.labelSmall,
                color = if (device.isConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

