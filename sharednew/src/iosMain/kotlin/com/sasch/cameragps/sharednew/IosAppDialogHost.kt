package com.sasch.cameragps.sharednew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.always_location
import cameragps.sharednew.generated.resources.cancel_button
import cameragps.sharednew.generated.resources.donation_dialog_confirm
import cameragps.sharednew.generated.resources.donation_dialog_dismiss
import cameragps.sharednew.generated.resources.donation_dialog_message
import cameragps.sharednew.generated.resources.donation_dialog_title
import cameragps.sharednew.generated.resources.further_help
import cameragps.sharednew.generated.resources.ios_accessory_migration_busy
import cameragps.sharednew.generated.resources.ios_accessory_migration_dialog_confirm
import cameragps.sharednew.generated.resources.ios_accessory_migration_dialog_later
import cameragps.sharednew.generated.resources.ios_accessory_migration_dialog_message
import cameragps.sharednew.generated.resources.ios_accessory_migration_dialog_title
import cameragps.sharednew.generated.resources.ios_accessory_migration_error_message
import cameragps.sharednew.generated.resources.ios_accessory_migration_error_retry
import cameragps.sharednew.generated.resources.ios_accessory_migration_error_title
import cameragps.sharednew.generated.resources.ios_accessory_migration_success
import cameragps.sharednew.generated.resources.ios_troubleshooting_got_it
import cameragps.sharednew.generated.resources.open_location_settings
import cameragps.sharednew.generated.resources.open_settings_for_always_location
import cameragps.sharednew.generated.resources.open_settings_for_precise_location
import cameragps.sharednew.generated.resources.pairing_failed_device_message
import cameragps.sharednew.generated.resources.pairing_failed_hint_camera_pairing
import cameragps.sharednew.generated.resources.pairing_failed_hint_intro
import cameragps.sharednew.generated.resources.pairing_failed_hint_pairing_mode
import cameragps.sharednew.generated.resources.pairing_failed_hint_phone_pairing
import cameragps.sharednew.generated.resources.pairing_failed_title
import cameragps.sharednew.generated.resources.precise_location
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.ui.dialog.DialogHost
import com.sasch.cameragps.sharednew.ui.settings.SharedSentryConsentDialog
import com.sasch.cameragps.sharednew.whatsnew.WhatsNewDialog
import com.sasch.cameragps.sharednew.whatsnew.WhatsNewState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/** Renders queued app prompts and reports navigation/settings actions to the app. */
@Composable
internal fun IosAppDialogHost(
    state: IosAppDialogState,
    canShow: Boolean,
    whatsNew: WhatsNewState,
    migrationSnackbarHostState: SnackbarHostState,
    onSentryEnabledChange: (Boolean) -> Unit,
    onOpenTroubleshooting: () -> Unit,
    onOpenTipJar: () -> Unit,
) {
    val bluetoothController = IosBluetoothController
    val migrationInProgress by bluetoothController.migrationInProgress.collectAsState()
    val pairingFailedDeviceName by bluetoothController.pairingFailedDevice.collectAsState()
    val migrationSuccessMessage = stringResource(Res.string.ios_accessory_migration_success)
    // This scope outlives individual dialogs, including migration completion/snackbars.
    val scope = rememberCoroutineScope()

    DialogHost(state.queue, canShow = canShow) { dialog, dismiss ->
        when (dialog) {
            IosDialog.MigrationError -> {
                val dismissError = {
                    bluetoothController.clearMigrationError()
                    dismiss()
                }
                AlertDialog(
                    onDismissRequest = { if (!migrationInProgress) dismissError() },
                    title = { Text(stringResource(Res.string.ios_accessory_migration_error_title)) },
                    text = {
                        if (migrationInProgress) {
                            MigrationBusyRow()
                        } else {
                            Text(stringResource(Res.string.ios_accessory_migration_error_message))
                        }
                    },
                    confirmButton = {
                        if (!migrationInProgress) {
                            TextButton(onClick = {
                                scope.launch {
                                    if (bluetoothController.presentMigrationPicker()) {
                                        dismiss()
                                        migrationSnackbarHostState.showSnackbar(
                                            migrationSuccessMessage
                                        )
                                    }
                                }
                            }) {
                                Text(stringResource(Res.string.ios_accessory_migration_error_retry))
                            }
                        }
                    },
                    dismissButton = {
                        if (!migrationInProgress) {
                            TextButton(onClick = { dismissError() }) {
                                Text(stringResource(Res.string.ios_accessory_migration_dialog_later))
                            }
                        }
                    },
                )
            }

            IosDialog.MigrationExplainer -> {
                AlertDialog(
                    onDismissRequest = { if (!migrationInProgress) dismiss() },
                    title = { Text(stringResource(Res.string.ios_accessory_migration_dialog_title)) },
                    text = {
                        if (migrationInProgress) {
                            MigrationBusyRow()
                        } else {
                            Text(stringResource(Res.string.ios_accessory_migration_dialog_message))
                        }
                    },
                    confirmButton = {
                        if (!migrationInProgress) {
                            TextButton(onClick = {
                                scope.launch {
                                    val migrated = bluetoothController.presentMigrationPicker()
                                    dismiss()
                                    if (migrated) {
                                        migrationSnackbarHostState.showSnackbar(
                                            migrationSuccessMessage
                                        )
                                    }
                                }
                            }) {
                                Text(stringResource(Res.string.ios_accessory_migration_dialog_confirm))
                            }
                        }
                    },
                    dismissButton = {
                        if (!migrationInProgress) {
                            TextButton(onClick = dismiss) {
                                Text(stringResource(Res.string.ios_accessory_migration_dialog_later))
                            }
                        }
                    },
                )
            }

            IosDialog.SentryConsent -> {
                SharedSentryConsentDialog(
                    onAllow = {
                        onSentryEnabledChange(true)
                        dismiss()
                    },
                    onDecline = {
                        onSentryEnabledChange(false)
                        dismiss()
                    },
                    onDontShowAgain = {
                        onSentryEnabledChange(false)
                        dismiss()
                    },
                )
            }

            is IosDialog.PairingFailed -> {
                val dismissFailure = {
                    if (pairingFailedDeviceName == dialog.deviceName) {
                        bluetoothController.clearPairingFailedDevice()
                    }
                    dismiss()
                }
                AlertDialog(
                    onDismissRequest = { dismissFailure() },
                    title = { Text(text = stringResource(Res.string.pairing_failed_title)) },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(
                                    Res.string.pairing_failed_device_message,
                                    dialog.deviceName
                                )
                            )
                            Text(stringResource(Res.string.pairing_failed_hint_intro))
                            Text(stringResource(Res.string.pairing_failed_hint_pairing_mode))
                            Text(stringResource(Res.string.pairing_failed_hint_camera_pairing))
                            Text(stringResource(Res.string.pairing_failed_hint_phone_pairing))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { dismissFailure() }) {
                            Text(stringResource(Res.string.ios_troubleshooting_got_it))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            dismissFailure()
                            onOpenTroubleshooting()
                        }) {
                            Text(stringResource(Res.string.further_help))
                        }
                    },
                )
            }

            IosDialog.AlwaysLocation -> {
                AlertDialog(
                    onDismissRequest = dismiss,
                    title = { Text(text = stringResource(Res.string.always_location)) },
                    text = { Text(text = stringResource(Res.string.open_settings_for_always_location)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                dismiss()
                                openAppSettings()
                            }
                        ) {
                            Text(text = stringResource(Res.string.open_location_settings))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = dismiss) {
                            Text(text = stringResource(Res.string.cancel_button))
                        }
                    }
                )
            }

            IosDialog.PreciseLocation -> {
                AlertDialog(
                    onDismissRequest = dismiss,
                    title = { Text(text = stringResource(Res.string.precise_location)) },
                    text = { Text(text = stringResource(Res.string.open_settings_for_precise_location)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                dismiss()
                                openAppSettings()
                            }
                        ) {
                            Text(text = stringResource(Res.string.open_location_settings))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = dismiss) {
                            Text(text = stringResource(Res.string.cancel_button))
                        }

                    }
                )
            }

            IosDialog.WhatsNew -> {
                whatsNew.release?.let { release ->
                    WhatsNewDialog(release, onDismiss = {
                        whatsNew.dismiss()
                        dismiss()
                    })
                }
            }

            IosDialog.Donation -> {
                // Count an impression only when the donation dialog actually becomes visible.
                LaunchedEffect(Unit) {
                    state.onDonationShown()
                }
                AlertDialog(
                    onDismissRequest = dismiss,
                    title = { Text(text = stringResource(Res.string.donation_dialog_title)) },
                    text = { Text(text = stringResource(Res.string.donation_dialog_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                dismiss()
                                onOpenTipJar()
                            }
                        ) {
                            Text(text = stringResource(Res.string.donation_dialog_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = dismiss) {
                            Text(text = stringResource(Res.string.donation_dialog_dismiss))
                        }
                    }
                )
            }

        }
    }
}

/**
 * Opens this app's page in the Settings app (one tap away from its Location
 * settings) — the deepest link Apple's public API allows.
 */
private fun openAppSettings() {
    val settingsUrl = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    if (UIApplication.sharedApplication.canOpenURL(settingsUrl)) {
        UIApplication.sharedApplication.openURL(settingsUrl, emptyMap<Any?, Any>(), {})
    }
}

/** Busy row shown inside the migration dialogs while an attempt is running. */
@Composable
private fun MigrationBusyRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(stringResource(Res.string.ios_accessory_migration_busy))
    }
}
