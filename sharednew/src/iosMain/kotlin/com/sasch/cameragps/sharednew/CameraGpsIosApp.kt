package com.sasch.cameragps.sharednew

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.diamondedge.logging.LogLevel
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.crash.IosCrashReporting
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import com.sasch.cameragps.sharednew.language.appLanguagePreference
import com.sasch.cameragps.sharednew.logging.IosLogFormatter
import com.sasch.cameragps.sharednew.logging.IosLogging
import com.sasch.cameragps.sharednew.logging.logIosLifecycle
import com.sasch.cameragps.sharednew.review.IosReviewPromptEffect
import com.sasch.cameragps.sharednew.ui.device.SharedDevicesScreen
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListViewModel
import com.sasch.cameragps.sharednew.ui.devicelist.IosDeviceListDataSource
import com.sasch.cameragps.sharednew.ui.logs.SharedLogViewerScreen
import com.sasch.cameragps.sharednew.ui.pairing.PairingPreparationState
import com.sasch.cameragps.sharednew.ui.pairing.SharedPairingPreparationScreen
import com.sasch.cameragps.sharednew.ui.welcome.SharedWelcomeScreen
import com.sasch.cameragps.sharednew.whatsnew.ReleasePlatform
import com.sasch.cameragps.sharednew.whatsnew.rememberWhatsNewState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.UIKit.UIApplicationState.UIApplicationStateActive
import platform.UIKit.UIScene
import platform.UIKit.UISceneDidActivateNotification
import platform.UIKit.UISceneDidDisconnectNotification
import platform.UIKit.UISceneDidEnterBackgroundNotification
import platform.UIKit.UISceneWillDeactivateNotification
import platform.UIKit.UISceneWillEnterForegroundNotification
import platform.UIKit.UIViewController

internal enum class IosScreen {
    Welcome,
    Devices,
    PairingPreparation,
    DeviceDetails,
    Settings,
    Help,
    Troubleshooting,
    Logs,
}

@Composable
internal fun CameraGpsIosApp(
    reviewTestMode: Boolean = false,
    requestReview: (UIViewController) -> Boolean
) {
    val selectedLanguage by appLanguagePreference.selected.collectAsState()
    val bluetoothController = IosBluetoothController
    val realDevices by bluetoothController.devices.collectAsState()
    val devices = if (SCREENSHOT_MODE) mockDevices else realDevices
    val listViewModel: DeviceListViewModel = viewModel {
        DeviceListViewModel(IosDeviceListDataSource)
    }

    val realListItems by listViewModel.items.collectAsState()
    val listItems = if (SCREENSHOT_MODE) mockDeviceListItems else realListItems
    val scope = rememberCoroutineScope()

    val migrationSnackbarHostState = remember { SnackbarHostState() }
    val pairingPreparation = remember { PairingPreparationState() }
    val isCameraPickerActive by pairingPreparation.isSearching.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val logRepository = remember { LogRepository(getDatabaseBuilder()) }
    var currentScreen by remember {
        mutableStateOf(
            if (IosAppPreferences.showWelcomeOnLaunch()) IosScreen.Welcome else IosScreen.Devices
        )
    }
    val whatsNew = rememberWhatsNewState(
        version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
            ?: "",
        platform = ReleasePlatform.Ios,
        previousVersion = IosAppPreferences.lastSeenReleaseVersion(),
        firstLaunch = IosAppPreferences.showWelcomeOnLaunch(),
        saveVersion = IosAppPreferences::setLastSeenReleaseVersion,
    )
    var isAppEnabled by remember { mutableStateOf(IosAppPreferences.isAppEnabled()) }
    var hapticsEnabled by remember { mutableStateOf(IosAppPreferences.isHapticsEnabled()) }
    var sentryEnabled by remember { mutableStateOf(IosAppPreferences.isSentryEnabled()) }
    var isAppInForeground by remember {
        mutableStateOf(
            UIApplication.sharedApplication.applicationState == UIApplicationStateActive
        )
    }

    var scrollToTipJarOnSettingsOpen by remember { mutableStateOf(false) }
    var selectedDeviceIdentifier by remember { mutableStateOf<String?>(null) }
    // Where the back button of the troubleshooting guide returns to (it can be opened
    // from the help screen as well as from the device-list dialogs).
    var troubleshootingReturnScreen by remember { mutableStateOf(IosScreen.Devices) }

    // Keep requests queued while another screen or a system picker is open.
    val canShowDialogs = !SCREENSHOT_MODE && currentScreen == IosScreen.Devices &&
            isAppInForeground && lifecycleState == Lifecycle.State.RESUMED && !isCameraPickerActive

    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        logIosLifecycle("app UI attached; registering lifecycle observers")
        val backgroundObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null
        ) { _ ->
            logIosLifecycle("UIApplication.didEnterBackground -> Wi-Fi disconnect requested")
            isAppInForeground = false
            IosBluetoothController.wifiRemote.disconnect()
        }
        val activeObserver = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null
        ) { _ ->
            logIosLifecycle("UIApplication.didBecomeActive")
            isAppInForeground = true
        }
        val diagnosticObservers = listOf(
            UIApplicationWillResignActiveNotification to "UIApplication.willResignActive",
            UIApplicationWillEnterForegroundNotification to "UIApplication.willEnterForeground",
            UIApplicationWillTerminateNotification to "UIApplication.willTerminate",
            UISceneWillDeactivateNotification to "UIScene.willDeactivate",
            UISceneDidEnterBackgroundNotification to "UIScene.didEnterBackground",
            UISceneWillEnterForegroundNotification to "UIScene.willEnterForeground",
            UISceneDidActivateNotification to "UIScene.didActivate",
            UISceneDidDisconnectNotification to "UIScene.didDisconnect",
        ).map { (name, event) ->
            center.addObserverForName(name, `object` = null, queue = null) { notification ->
                logIosLifecycle(event, notification?.`object` as? UIScene)
            }
        }

        onDispose {
            logIosLifecycle("app UI disposed; removing lifecycle observers")
            center.removeObserver(backgroundObserver)
            center.removeObserver(activeObserver)
            diagnosticObservers.forEach(center::removeObserver)
        }
    }

    LaunchedEffect(Unit) {
        IosLogging.install(logRepository, LogLevel.valueOf(IosAppPreferences.getLogLevel()))
    }

    LaunchedEffect(lifecycleState) {
        logIosLifecycle("Compose lifecycle=$lifecycleState")
        if (lifecycleState == Lifecycle.State.RESUMED) appLanguagePreference.refresh()
    }
    val migrationCandidates by bluetoothController.migrationCandidates.collectAsState()
    val migrationInProgress by bluetoothController.migrationInProgress.collectAsState()
    val transmissionNotificationsEnabled by bluetoothController.transmissionNotificationsEnabled.collectAsState()
    val transmissionNotificationsPermissionDenied by bluetoothController.transmissionNotificationsPermissionDenied.collectAsState()

    val dialogs = rememberIosAppDialogState(
        isDeviceScreen = currentScreen == IosScreen.Devices,
        isAppInForeground = isAppInForeground,
        lifecycleState = lifecycleState,
        devices = devices,
        whatsNewPending = whatsNew.pending,
    )
    val onSentryEnabledChange: (Boolean) -> Unit = { enabled ->
        sentryEnabled = enabled
        IosAppPreferences.setSentryEnabled(enabled)
        // A choice in Settings also answers any pending consent prompt.
        IosAppPreferences.setSentryConsentDialogDismissed(true)
        dialogs.dismissSentryConsent()
        // Enabling takes effect now; disabling requires the restart shown in Settings.
        if (enabled) IosCrashReporting.start()
    }

    IosReviewPromptEffect(
        reviewTestMode = reviewTestMode,
        isForeground = !SCREENSHOT_MODE && isAppInForeground,
        hasSavedCamera = devices.any { it.isSaved },
        canPresent = currentScreen == IosScreen.Devices &&
                lifecycleState == Lifecycle.State.RESUMED && isAppEnabled,
        hasCompetingPrompt = dialogs.hasPendingDialogs || migrationInProgress || isCameraPickerActive,
        requestReview = requestReview,
    )

    // Compose resources read the system language on iOS. Changing AppleLanguages
    // and changing this key together refreshes the resource tree immediately;
    // Bluetooth and navigation state remain outside the keyed subtree.
    key(selectedLanguage?.tag ?: "system") {
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
                    snackbarHost = { SnackbarHost(migrationSnackbarHostState) },
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
                        items = listItems,
                        isAppEnabled = isAppEnabled,
                        hapticsEnabled = hapticsEnabled,
                        migrationCandidates = migrationCandidates,
                        onMigrate = dialogs::requestMigration,
                        onAddCamera = { currentScreen = IosScreen.PairingPreparation },
                        onOpenSettings = { currentScreen = IosScreen.Settings },
                        onOpenHelp = {
                            troubleshootingReturnScreen = IosScreen.Devices
                            currentScreen = IosScreen.Troubleshooting
                        },
                        onConnect = { device ->
                            scope.launch {
                                if (!device.isConnected) {
                                    bluetoothController.connect(device.identifier)
                                }
                            }
                        },
                        onTriggerRemoteShutter = { device ->
                            scope.launch {
                                bluetoothController.triggerShutterSequence(device.identifier)
                            }
                        },
                        onDelete = { device ->
                            scope.launch {
                                bluetoothController.forgetDevice(device.identifier)
                            }
                        },
                        onOpenDetails = { device ->
                            selectedDeviceIdentifier = device.identifier
                            currentScreen = IosScreen.DeviceDetails
                        },
                    )
                }
            }

            IosScreen.PairingPreparation -> {
                SharedPairingPreparationScreen(
                    isSearching = isCameraPickerActive,
                    onSearch = {
                        scope.launch {
                            if (pairingPreparation.search { bluetoothController.presentAccessoryPicker() }) {
                                currentScreen = IosScreen.Devices
                            }
                        }
                    },
                    onBack = { currentScreen = IosScreen.Devices },
                    onHelp = {
                        troubleshootingReturnScreen = IosScreen.PairingPreparation
                        currentScreen = IosScreen.Troubleshooting
                    },
                )
            }

            IosScreen.DeviceDetails -> {
                val selectedDevice =
                    devices.firstOrNull { it.identifier == selectedDeviceIdentifier }
                if (selectedDevice == null) {
                    LaunchedEffect(Unit) {
                        currentScreen = IosScreen.Devices
                    }
                } else {
                    IosDeviceDetailScreen(
                        device = selectedDevice,
                        onBackClick = { currentScreen = IosScreen.Devices },
                    )
                }
            }

            IosScreen.Settings -> {
                IosSettingsScreen(
                    whatsNew = whatsNew,
                    isAppEnabled = isAppEnabled,
                    transmissionNotificationsEnabled = transmissionNotificationsEnabled,
                    transmissionNotificationsPermissionDenied = transmissionNotificationsPermissionDenied,
                    onTransmissionNotificationsEnabledChange = bluetoothController::setTransmissionNotificationsEnabled,
                    onOpenNotificationSettings = { openNotificationSettings() },
                    scrollToTipJarOnOpen = scrollToTipJarOnSettingsOpen,
                    onBackClick = { currentScreen = IosScreen.Devices },
                    onAppEnabledChange = { enabled ->
                        isAppEnabled = enabled
                        IosAppPreferences.setAppEnabled(enabled)
                        scope.launch {
                            bluetoothController.applyAppEnabledState(enabled)
                        }
                    },
                    hapticsEnabled = hapticsEnabled,
                    onHapticsEnabledChange = { enabled ->
                        hapticsEnabled = enabled
                        IosAppPreferences.setHapticsEnabled(enabled)
                    },
                    sentryEnabled = sentryEnabled,
                    onSentryEnabledChange = onSentryEnabledChange,
                    onChangeLogLevel = { level ->
                        IosLogging.install(logRepository, level)
                    },
                    onTipJarScrollConsumed = {
                        scrollToTipJarOnSettingsOpen = false
                    }
                )
            }

            IosScreen.Help -> {
                IosHelpScreen(
                    onBackClick = { currentScreen = IosScreen.Devices },
                    onOpenTroubleshooting = {
                        troubleshootingReturnScreen = IosScreen.Help
                        currentScreen = IosScreen.Troubleshooting
                    },
                )
            }

            IosScreen.Troubleshooting -> {
                IosTroubleshootingScreen(
                    onBackClick = { currentScreen = troubleshootingReturnScreen }
                )
            }

            IosScreen.Logs -> {
                val logFormatter = remember(logRepository) { IosLogFormatter(logRepository) }
                SharedLogViewerScreen(
                    logFormatter = logFormatter,
                    logRepository = logRepository,
                    onBackClick = { currentScreen = IosScreen.Devices }
                )
            }
        }
    }

    IosAppDialogHost(
        state = dialogs,
        canShow = canShowDialogs,
        whatsNew = whatsNew,
        migrationSnackbarHostState = migrationSnackbarHostState,
        onSentryEnabledChange = onSentryEnabledChange,
        onOpenTroubleshooting = {
            troubleshootingReturnScreen = IosScreen.Devices
            currentScreen = IosScreen.Troubleshooting
        },
        onOpenTipJar = {
            scrollToTipJarOnSettingsOpen = true
            currentScreen = IosScreen.Settings
        },
    )
}

/**
 * Opens this app's notification settings so banners, sounds and the lock screen can be
 * customized there; falls back to the app's Settings page when iOS refuses the deep link.
 */
private fun openNotificationSettings() {
    val settingsUrl = notificationSettingsUrl() ?: return
    UIApplication.sharedApplication.openURL(settingsUrl, emptyMap<Any?, Any>(), {})
}
