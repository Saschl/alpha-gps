package com.sasch.cameragps.sharednew

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.crash.CrashReportPolicy
import com.sasch.cameragps.sharednew.crash.IosCrashReporting
import com.sasch.cameragps.sharednew.ui.dialog.DialogQueue

internal sealed interface IosDialog {
    data object MigrationError : IosDialog
    data object MigrationExplainer : IosDialog
    data object SentryConsent : IosDialog
    data class PairingFailed(val deviceName: String) : IosDialog
    data object AlwaysLocation : IosDialog
    data object PreciseLocation : IosDialog
    data object WhatsNew : IosDialog
    data object Donation : IosDialog
}

/** Owns app dialog requests and donation bookkeeping for this composition's lifetime. */
internal class IosAppDialogState {
    val queue = DialogQueue<IosDialog>()
    val hasPendingDialogs: Boolean get() = !queue.isEmpty
    private var recordDonationImpression = false

    fun requestMigration() = queue.enqueue(IosDialog.MigrationExplainer)

    fun dismissSentryConsent() = queue.remove(IosDialog.SentryConsent)

    fun requestDonation(recordImpression: Boolean = false) {
        if (IosDialog.Donation in queue) return
        recordDonationImpression = recordImpression
        queue.enqueue(IosDialog.Donation)
    }

    fun onDonationShown() {
        if (!recordDonationImpression) return
        IosAppPreferences.setDonationHintShownNow()
        IosAppPreferences.increaseDonationHintShownTimes()
        recordDonationImpression = false
    }
}

/** Translates startup, permission and Bluetooth changes into explicit queue requests. */
@Composable
internal fun rememberIosAppDialogState(
    isDeviceScreen: Boolean,
    isAppInForeground: Boolean,
    lifecycleState: Lifecycle.State,
    devices: List<BluetoothDeviceInfo>,
    whatsNewPending: Boolean,
): IosAppDialogState {
    val state = remember { IosAppDialogState() }
    val dialogQueue = state.queue
    val bluetoothController = IosBluetoothController
    val migrationCandidates by bluetoothController.migrationCandidates.collectAsState()
    val migrationError by bluetoothController.migrationError.collectAsState()
    val pairingFailedDeviceName by bluetoothController.pairingFailedDevice.collectAsState()
    val needsAlwaysLocationAuthorization by
    bluetoothController.needsAlwaysLocationAuthorization.collectAsState()

    LaunchedEffect(Unit) {
        if (!SCREENSHOT_MODE) {
            if (CrashReportPolicy.shouldShowConsentDialog(
                    available = IosCrashReporting.AVAILABLE,
                    consentDialogDismissed = IosAppPreferences.isSentryConsentDialogDismissed(),
                )
            ) {
                dialogQueue.enqueue(IosDialog.SentryConsent)
            }
            if (IosAppPreferences.consumeForceDonationDialogOnNextAppStart()) {
                state.requestDonation()
            }
        }
    }

    LaunchedEffect(lifecycleState) {
        if (lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        if (!SCREENSHOT_MODE && !bluetoothController.hasPreciseAccuracyAuthorization()) {
            dialogQueue.enqueue(IosDialog.PreciseLocation)
        } else {
            dialogQueue.remove(IosDialog.PreciseLocation)
        }
    }

    LaunchedEffect(migrationCandidates, isAppInForeground) {
        if (SCREENSHOT_MODE) return@LaunchedEffect
        if (migrationCandidates.isEmpty()) {
            dialogQueue.remove(IosDialog.MigrationExplainer)
        }
        if (!isAppInForeground) return@LaunchedEffect
        // Explain before the system sheet appears, rather than letting it show
        // up unannounced. Continue then opens the picker as a user action.
        if (bluetoothController.consumeAutoMigrationPrompt()) {
            dialogQueue.enqueue(IosDialog.MigrationExplainer)
        }
    }

    LaunchedEffect(migrationError) {
        if (!SCREENSHOT_MODE && migrationError) {
            dialogQueue.enqueue(IosDialog.MigrationError)
        } else {
            dialogQueue.remove(IosDialog.MigrationError)
        }
    }

    LaunchedEffect(pairingFailedDeviceName) {
        // Replace obsolete failures while keeping the device name with its request.
        dialogQueue.removeAll { it is IosDialog.PairingFailed && it.deviceName != pairingFailedDeviceName }
        if (!SCREENSHOT_MODE) {
            pairingFailedDeviceName?.let { dialogQueue.enqueue(IosDialog.PairingFailed(it)) }
        }
    }

    LaunchedEffect(needsAlwaysLocationAuthorization, lifecycleState) {
        if (!needsAlwaysLocationAuthorization) {
            dialogQueue.remove(IosDialog.AlwaysLocation)
        } else if (!SCREENSHOT_MODE && lifecycleState == Lifecycle.State.RESUMED) {
            // A dismissed hint may be requested again when the app resumes.
            dialogQueue.enqueue(IosDialog.AlwaysLocation)
        }
    }

    LaunchedEffect(whatsNewPending) {
        if (!SCREENSHOT_MODE && whatsNewPending) {
            dialogQueue.enqueue(IosDialog.WhatsNew)
        } else {
            dialogQueue.remove(IosDialog.WhatsNew)
        }
    }

    LaunchedEffect(isDeviceScreen, isAppInForeground, devices) {
        if (SCREENSHOT_MODE || !isDeviceScreen || !isAppInForeground) {
            return@LaunchedEffect
        }
        if (IosDialog.Donation !in dialogQueue && devices.isNotEmpty() &&
            IosAppPreferences.donationHintLastShownDaysAgo(initialize = true) >= 30 &&
            IosAppPreferences.donationHintShownTimes() < 1
        ) {
            state.requestDonation(recordImpression = true)
        }
    }

    return state
}
