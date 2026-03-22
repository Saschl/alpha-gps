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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_disabled_message
import cameragps.sharednew.generated.resources.app_disabled_title
import cameragps.sharednew.generated.resources.app_settings
import cameragps.sharednew.generated.resources.connected
import cameragps.sharednew.generated.resources.ios_no_devices_message
import cameragps.sharednew.generated.resources.scanning_for_cameras
import cameragps.sharednew.generated.resources.scanning_paused_message
import cameragps.sharednew.generated.resources.scanning_paused_title
import cameragps.sharednew.generated.resources.tap_to_connect
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DeviceListContent(
    devices: List<BluetoothDeviceInfo>,
    isAppEnabled: Boolean,
    isScanning: Boolean,
    onOpenSettings: () -> Unit,
    onConnect: (BluetoothDeviceInfo) -> Unit,
) {
    when {
        !isAppEnabled -> {
            EmptyStateCard(
                title = stringResource(Res.string.app_disabled_title),
                message = stringResource(Res.string.app_disabled_message),
                actionLabel = stringResource(Res.string.app_settings),
                onAction = onOpenSettings,
            )
        }

        devices.isEmpty() && !isScanning -> {
            EmptyStateCard(
                title = stringResource(Res.string.scanning_paused_title),
                message = stringResource(Res.string.scanning_paused_message),
                actionLabel = stringResource(Res.string.app_settings),
                onAction = onOpenSettings,
            )
        }

        devices.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(Res.string.scanning_for_cameras),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(Res.string.ios_no_devices_message),
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
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
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
                text = if (device.isConnected) {
                    stringResource(Res.string.connected)
                } else {
                    stringResource(Res.string.tap_to_connect)
                },
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


