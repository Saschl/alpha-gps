package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_settings
import cameragps.sharednew.generated.resources.dismiss_button
import cameragps.sharednew.generated.resources.wifi_connection_help
import cameragps.sharednew.generated.resources.wifi_connection_help_message
import cameragps.sharednew.generated.resources.wifi_connection_help_title
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteFailure
import org.jetbrains.compose.resources.stringResource

internal enum class WifiConnectionHelpMode { Automatic, OnRequest }

internal fun WifiRemoteFailure?.connectionHelpMode(): WifiConnectionHelpMode? = when (this) {
    WifiRemoteFailure.NetworkConnectionFailed, WifiRemoteFailure.NetworkPermissionDenied ->
        WifiConnectionHelpMode.Automatic

    WifiRemoteFailure.CameraUnavailable, WifiRemoteFailure.NetworkLost,
    WifiRemoteFailure.NetworkJoinFailed, WifiRemoteFailure.ProtocolError -> WifiConnectionHelpMode.OnRequest

    else -> null
}

/** Android hosts supply the app-settings action; no vendor-specific settings intents. */
@Composable
internal fun WifiConnectionHelp(failure: WifiRemoteFailure?, onAppSettings: () -> Unit) {
    val mode = failure.connectionHelpMode()
    var showDialog by rememberSaveable(failure) {
        mutableStateOf(mode == WifiConnectionHelpMode.Automatic)
    }
    if (mode == null) return

    TextButton(onClick = { showDialog = true }) {
        Text(stringResource(Res.string.wifi_connection_help))
    }
    if (showDialog) AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(stringResource(Res.string.wifi_connection_help_title)) },
        text = {
            Text(
                stringResource(Res.string.wifi_connection_help_message),
                Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = { showDialog = false; onAppSettings() }) {
                Text(stringResource(Res.string.app_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = { showDialog = false }) {
                Text(stringResource(Res.string.dismiss_button))
            }
        },
    )
}
