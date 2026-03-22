package com.saschl.cameragps

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.saschl.cameragps.database.LogDatabase
import com.saschl.cameragps.service.FileTree
import com.saschl.cameragps.service.GlobalExceptionHandler
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.ui.HelpScreen
import com.saschl.cameragps.ui.SentryConsentDialog
import com.saschl.cameragps.ui.WelcomeScreen
import com.saschl.cameragps.ui.device.CameraDeviceManager
import com.saschl.cameragps.ui.settings.SettingsScreen
import com.saschl.cameragps.ui.theme.CameraGpsTheme
import com.saschl.cameragps.utils.PreferencesManager
import com.saschl.cameragps.utils.SentryInit
import kotlinx.serialization.Serializable
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Sentry will be initialized by the consent dialog or if already consented
        if (PreferencesManager.sentryEnabled(this) && PreferencesManager.isSentryConsentDialogDismissed(
                this
            )
        ) {
            SentryInit.initSentry(this)
        }

        if (Timber.forest().find { it is FileTree } == null) {
            val logLevel = PreferencesManager.logLevel(this)
            FileTree.initialize(this)
            Timber.plant(FileTree(this, logLevel))

            // Set up global exception handler to log crashes
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))
        }

        setContent {
            CameraGpsTheme {
                AppContent()
            }
        }
    }

    @Composable
    private fun AppContent() {
        val context = LocalContext.current
        val cameraDeviceDAO = LogDatabase.getDatabase(context).cameraDeviceDao()
        val lifecycleState by ProcessLifecycleOwner.get().lifecycle.currentStateFlow.collectAsState()

        val startDestination = remember {
            if (PreferencesManager.isFirstLaunch(context)) {
                AppDestination.Welcome
            } else {
                AppDestination.Devices
            }
        }
        val backStack = rememberNavBackStack(startDestination)

        LaunchedEffect(lifecycleState) {
            Timber.d("Lifecycle state changed: $lifecycleState")
            when (lifecycleState) {
                Lifecycle.State.RESUMED -> {
                    if (PreferencesManager.isFirstLaunch(context)) {
                        backStack.clear()
                        backStack.add(AppDestination.Welcome)
                    }
                }
                else -> { /* No action needed */ }
            }
        }

        LaunchedEffect(lifecycleState) {
            when (lifecycleState) {
                Lifecycle.State.RESUMED -> {
                    Timber.d("App started, will resume transmission for configured devices")
                    cameraDeviceDAO.getAllCameraDevices().forEach {
                        val shouldTransmissionStart =
                            it.deviceEnabled
                                    && it.alwaysOnEnabled
                                    && PreferencesManager.isAppEnabled(context.applicationContext)
                        if (shouldTransmissionStart) {
                            Timber.d("Resuming location transmission for device ${it.mac}")
                            val intent = Intent(context, LocationSenderService::class.java)
                            intent.putExtra("address", it.mac.uppercase())
                            context.startForegroundService(intent)
                        }
                    }
                }

                else -> { /* No action needed */ }
            }
        }

        var showSentryDialog by remember {
            mutableStateOf(
                !PreferencesManager.isSentryConsentDialogDismissed(context)
            )
        }

        NavDisplay(
            backStack = backStack,
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            }
        ) { destination ->
            when (destination) {
                AppDestination.Welcome -> {
                    NavEntry(AppDestination.Welcome) {
                        WelcomeScreen(
                            onGetStarted = {
                                PreferencesManager.setFirstLaunchCompleted(context)
                                backStack.clear()
                                backStack.add(AppDestination.Devices)
                                Timber.i("Welcome screen completed, navigating to main app")
                            }
                        )
                    }
                }

                AppDestination.Settings -> {
                    NavEntry(AppDestination.Settings) {
                        SettingsScreen(
                            onBackClick = {
                                backStack.removeAt(backStack.lastIndex)
                            }
                        )
                    }
                }

                AppDestination.Help -> {
                    NavEntry(AppDestination.Help) {
                        HelpScreen(
                            onBackClick = {
                                backStack.removeAt(backStack.lastIndex)
                            }
                        )
                    }
                }

                AppDestination.Devices -> {
                    NavEntry(AppDestination.Devices) {
                        LaunchedEffect(Unit) {
                            showSentryDialog =
                                !PreferencesManager.isSentryConsentDialogDismissed(context)
                        }

                        CameraDeviceManager(
                            onSettingsClick = {
                                backStack.add(AppDestination.Settings)
                            },
                            onHelpClick = {
                                backStack.add(AppDestination.Help)
                            }
                        )

                        if (showSentryDialog) {
                            SentryConsentDialog(
                                onDismiss = {
                                    showSentryDialog = false
                                }
                            )
                        }
                    }
                }

                else -> {
                    NavEntry(destination) {
                        // Unknown destination fallback
                    }
                }
            }
        }
    }
}

private sealed interface AppDestination : NavKey {
    @Serializable
    data object Welcome : AppDestination
    @Serializable
    data object Devices : AppDestination
    @Serializable
    data object Settings : AppDestination

    @Serializable
    data object Help : AppDestination
}
