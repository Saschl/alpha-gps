package com.saschl.cameragps.ui.device

import android.content.Context
import android.content.Intent
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailServiceActions
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.service.ServiceCommandRouter

class AndroidDeviceDetailServiceActions(
    private val context: Context,
) : DeviceDetailServiceActions {

    override fun startAlwaysOn(deviceAddress: String) {
        val normalized = deviceAddress.uppercase()
        val intent = Intent(context, LocationSenderService::class.java).apply {
            putExtra("address", normalized)
        }
        context.startForegroundService(intent)
    }

    override fun requestShutdown(deviceAddress: String) {
        val normalized = deviceAddress.uppercase()
        val shutdownIntent = Intent(context, LocationSenderService::class.java).apply {
            action = SonyBluetoothConstants.ACTION_REQUEST_SHUTDOWN
            putExtra("address", normalized)
        }
        context.startService(shutdownIntent)
    }

    override fun setRemoteControlMonitoring(deviceAddress: String, enabled: Boolean) {
        val normalized = deviceAddress.uppercase()
        val intent = Intent(context, LocationSenderService::class.java).apply {
            action = SonyBluetoothConstants.ACTION_SET_REMOTE_CONTROL_MONITORING
            putExtra("address", normalized)
            putExtra(ServiceCommandRouter.EXTRA_REMOTE_CONTROL_ENABLED, enabled)
        }
        context.startService(intent)
    }
}
