package com.saschl.cameragps.shared

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.saschl.cameragps.shared.bluetooth.BluetoothDeviceInfo
import com.saschl.cameragps.shared.bluetooth.IosBluetoothController
import com.saschl.cameragps.shared.ui.device.SharedDevicesScreen
import platform.UIKit.UIViewController

/**
 * Entry point for the iOS host application.
 *
 * The Xcode project should call:
 *   CameraGpsShared.MainViewControllerKt.MainViewController()
 * and embed the returned UIViewController as the root view controller.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    MaterialTheme {
        CameraGpsIosApp()
    }
}

@Composable
private fun CameraGpsIosApp() {
    val bluetoothController = remember { IosBluetoothController() }
    val devices by bluetoothController.devices.collectAsState()

    LaunchedEffect(Unit) {
        bluetoothController.startScan()
    }

    SharedDevicesScreen(
        title = "CameraGPS",
        topBarActions = {},
    ) { innerPadding ->
        DeviceListContent(
            devices = devices,
            innerPadding = innerPadding,
            onConnect = { device ->
                // Connection is initiated from a coroutine via LaunchedEffect in the card
            },
        )
    }
}

@Composable
private fun DeviceListContent(
    devices: List<BluetoothDeviceInfo>,
    innerPadding: PaddingValues,
    onConnect: (BluetoothDeviceInfo) -> Unit,
) {
    if (devices.isEmpty()) {
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
                    text = "Scanning for cameras…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
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
            if (device.isConnected) {
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

