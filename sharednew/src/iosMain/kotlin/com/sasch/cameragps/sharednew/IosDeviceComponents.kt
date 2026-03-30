package com.sasch.cameragps.sharednew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_disabled_message
import cameragps.sharednew.generated.resources.app_disabled_title
import cameragps.sharednew.generated.resources.app_settings
import cameragps.sharednew.generated.resources.cancel
import cameragps.sharednew.generated.resources.connected
import cameragps.sharednew.generated.resources.delete
import cameragps.sharednew.generated.resources.delete_24px
import cameragps.sharednew.generated.resources.delete_device
import cameragps.sharednew.generated.resources.delete_device_confirmation
import cameragps.sharednew.generated.resources.enable_pairing_mode_continue
import cameragps.sharednew.generated.resources.enable_pairing_mode_message
import cameragps.sharednew.generated.resources.enable_pairing_mode_title
import cameragps.sharednew.generated.resources.ios_no_devices_message
import cameragps.sharednew.generated.resources.nearby_cameras
import cameragps.sharednew.generated.resources.saved_devices
import cameragps.sharednew.generated.resources.scanning_for_cameras
import cameragps.sharednew.generated.resources.scanning_paused_message
import cameragps.sharednew.generated.resources.scanning_paused_title
import cameragps.sharednew.generated.resources.tap_to_connect
import cameragps.sharednew.generated.resources.transmission_active
import cameragps.sharednew.generated.resources.transmission_inactive
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.ui.TransmissionDot
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DeviceListContent(
    devices: List<BluetoothDeviceInfo>,
    isAppEnabled: Boolean,
    isScanning: Boolean,
    onOpenSettings: () -> Unit,
    onConnect: (BluetoothDeviceInfo) -> Unit,
    onDelete: (BluetoothDeviceInfo) -> Unit,
) {
    var deviceToDelete by remember { mutableStateOf<BluetoothDeviceInfo?>(null) }
    var deviceToPair by remember { mutableStateOf<BluetoothDeviceInfo?>(null) }

    // Pairing mode hint dialog for first-time connections
    deviceToPair?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToPair = null },
            title = { Text(stringResource(Res.string.enable_pairing_mode_title)) },
            text = {
                Text(stringResource(Res.string.enable_pairing_mode_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConnect(device)
                        deviceToPair = null
                    }
                ) {
                    Text(stringResource(Res.string.enable_pairing_mode_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToPair = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    deviceToDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            title = { Text(stringResource(Res.string.delete_device)) },
            text = {
                Text(
                    stringResource(Res.string.delete_device_confirmation, device.name)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(device)
                        deviceToDelete = null
                    }
                ) {
                    Text(
                        text = stringResource(Res.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToDelete = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

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
                    modifier = Modifier.padding(horizontal = 16.dp),
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
            val savedDevices = devices.filter { it.isSaved }
            val nearbyDevices = devices.filter { !it.isSaved }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                if (savedDevices.isNotEmpty()) {
                    item(key = "header_saved") {
                        SectionHeader(title = stringResource(Res.string.saved_devices))
                    }
                    items(savedDevices, key = { it.identifier }) { device ->
                        SwipeToDeleteDeviceCard(
                            device = device,
                            onConnect = { onConnect(device) },
                            onDeleteRequest = { deviceToDelete = device },
                        )
                    }
                }

                if (savedDevices.isNotEmpty() && nearbyDevices.isNotEmpty()) {
                    item(key = "divider") {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }

                if (nearbyDevices.isNotEmpty()) {
                    item(key = "header_nearby") {
                        SectionHeader(title = stringResource(Res.string.nearby_cameras))
                    }
                    items(nearbyDevices, key = { it.identifier }) { device ->
                        DeviceCard(device = device, onConnect = { deviceToPair = device })
                    }
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
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun SwipeToDeleteDeviceCard(
    device: BluetoothDeviceInfo,
    onConnect: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDeleteRequest()
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val isActive = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = if (isActive) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    modifier = Modifier.padding(end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.delete_24px),
                        contentDescription = stringResource(Res.string.delete_device),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
    ) {
        DeviceCard(device = device, onConnect = onConnect)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                val isTransmissionActive = device.isTransmissionActive
                val transmissionStatusDescription = if (isTransmissionActive) {
                    stringResource(Res.string.transmission_active)
                } else {
                    stringResource(Res.string.transmission_inactive)
                }
                TransmissionDot(
                    isTransmissionActive,
                    modifier = Modifier.semantics {
                        contentDescription = transmissionStatusDescription
                    }
                )
            }
            Text(
                text = if (device.isConnected) {
                    stringResource(Res.string.connected)
                } else {
                    stringResource(Res.string.tap_to_connect)
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (device.isConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}


