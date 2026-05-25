package com.sasch.cameragps.sharednew.ui.device

import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController

/**
 * iOS implementation of [DeviceDetailServiceActions].
 * On iOS there is no foreground service concept, so always-on/shutdown are no-ops.
 * Remote control monitoring is delegated to the BLE controller.
 */
class IosDeviceDetailServiceActions : DeviceDetailServiceActions {

    override fun startAlwaysOn(deviceAddress: String) {
        // No foreground service on iOS — connection persistence is handled by CoreBluetooth restore
    }

    override fun requestShutdown(deviceAddress: String) {
        // No service shutdown concept on iOS
    }

    override fun setRemoteControlMonitoring(deviceAddress: String, enabled: Boolean) {
        IosBluetoothController.setRemoteStatusMonitoringEnabled(deviceAddress, enabled)
    }
}

