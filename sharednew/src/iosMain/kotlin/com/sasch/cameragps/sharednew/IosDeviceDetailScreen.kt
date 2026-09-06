package com.sasch.cameragps.sharednew

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.arrow_back_24px
import cameragps.sharednew.generated.resources.back
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController.ensureDeviceRecord
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailContent
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailDataSource
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailViewModel
import com.sasch.cameragps.sharednew.ui.device.IosDeviceDetailServiceActions
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsScreen
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource


@Composable
internal fun IosDeviceDetailScreen(
    device: BluetoothDeviceInfo,
    onBackClick: () -> Unit,
) {

    val deviceDao = remember(Unit) { IosBluetoothController.deviceDao }

    val deviceDetailDataSource: DeviceDetailDataSource = object : DeviceDetailDataSource {
        override suspend fun ensureDeviceExists(deviceId: String, deviceName: String?) {
            ensureDeviceRecord(deviceId.uppercase(), deviceName)
        }

        override suspend fun isDeviceEnabled(deviceId: String): Boolean {
            return deviceDao.isDeviceEnabled(deviceId.uppercase())
        }

        override suspend fun isAlwaysOnEnabled(deviceId: String): Boolean {
            return deviceDao.isDeviceAlwaysOnEnabled(deviceId.uppercase())
        }

        override suspend fun isRemoteControlEnabled(deviceId: String): Boolean {
            return deviceDao.isRemoteControlEnabled(deviceId.uppercase())
        }

        override suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean) {
            deviceDao.setDeviceEnabled(deviceId.uppercase(), enabled)
        }

        override suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean) {
            deviceDao.setAlwaysOnEnabled(deviceId.uppercase(), enabled)
        }

        override suspend fun setRemoteControlEnabled(deviceId: String, enabled: Boolean) {
            deviceDao.setRemoteControlEnabled(deviceId.uppercase(), enabled)
        }

        override suspend fun getHandshakeDelayMs(deviceId: String): Long {
            return deviceDao.getHandshakeDelayMs(deviceId.uppercase()) ?: 0L
        }

        override suspend fun setHandshakeDelayMs(deviceId: String, delayMs: Long) {
            deviceDao.setHandshakeDelayMs(deviceId.uppercase(), delayMs)
        }

        override suspend fun getDeviceName(deviceId: String): String? {
            return deviceDao.getDeviceName(deviceId.uppercase())
        }

        override suspend fun setDeviceName(deviceId: String, name: String) {
            deviceDao.setDeviceName(deviceId.uppercase(), name, isCustom = true)
            IosBluetoothController.refreshDeviceNames()
        }
    }
    val canRenameInSystem = remember(device.identifier) {
        IosBluetoothController.canRenameInSystem(device.identifier)
    }

    val viewModel: DeviceDetailViewModel = viewModel(key = device.identifier) {
        DeviceDetailViewModel(
            dataSource = deviceDetailDataSource,
            serviceActions = IosDeviceDetailServiceActions(),
        )
    }

    SharedSettingsScreen(
        title = device.name,
        onBackClick = onBackClick,
        onTitleClick = {},
        navigationIcon = {
            Icon(
                painterResource(Res.drawable.arrow_back_24px),
                contentDescription = stringResource(Res.string.back)
            )
        },
    ) { paddingValues ->
        DeviceDetailContent(
            viewModel = viewModel,
            deviceId = device.identifier,
            deviceName = device.name,
            modifier = Modifier.padding(paddingValues),
            onDeviceEnabledChanged = { enabled ->
                IosBluetoothController.applyDeviceEnabledState(device.identifier, enabled)
            },
            // Authorized accessories rename in the system record so the app and
            // iOS Settings agree; cameras paired before AccessorySetupKit have no
            // such record and fall back to the shared in-app dialog.
            onPresentSystemRename = if (canRenameInSystem) {
                { IosBluetoothController.presentSystemRename(device.identifier) }
            } else {
                null
            },
        )
    }
}


