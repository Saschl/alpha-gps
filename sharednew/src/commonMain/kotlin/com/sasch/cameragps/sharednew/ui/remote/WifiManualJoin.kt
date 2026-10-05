package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.wifi_remote_manual_join_continue
import cameragps.sharednew.generated.resources.wifi_remote_manual_join_hint
import cameragps.sharednew.generated.resources.wifi_remote_network_name
import cameragps.sharednew.generated.resources.wifi_remote_network_password
import cameragps.sharednew.generated.resources.wifi_remote_settings
import com.sasch.cameragps.sharednew.remote.wifi.CameraWifiCredentials
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WifiManualJoin(
    credentials: CameraWifiCredentials,
    onWifiSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.wifi_remote_manual_join_hint))
        OutlinedTextField(
            credentials.ssid,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(Res.string.wifi_remote_network_name)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            credentials.password,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(Res.string.wifi_remote_network_password)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = onWifiSettings) { Text(stringResource(Res.string.wifi_remote_settings)) }
        Button(onClick = onContinue) { Text(stringResource(Res.string.wifi_remote_manual_join_continue)) }
    }
}
