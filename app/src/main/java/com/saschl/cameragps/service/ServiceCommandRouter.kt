package com.saschl.cameragps.service

import android.content.Intent
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import java.util.Locale

class ServiceCommandRouter {
    companion object {
        const val EXTRA_REMOTE_CONTROL_ENABLED = "remoteControlEnabled"
    }

    fun route(intent: Intent?): ServiceCommand {
        val action = intent?.action
        val address = intent?.getStringExtra("address")?.uppercase(Locale.getDefault())

        if (action == SonyBluetoothConstants.ACTION_SET_REMOTE_CONTROL_MONITORING) {
            val enabled = intent.getBooleanExtra(EXTRA_REMOTE_CONTROL_ENABLED, false)
            return if (address == null) {
                ServiceCommand.Ignore("Remote control monitoring toggle ignored because address is missing")
            } else {
                ServiceCommand.SetRemoteControlMonitoring(address, enabled)
            }
        }

        if (action == SonyBluetoothConstants.ACTION_TRIGGER_REMOTE_SHUTTER) {
            return if (address == null) {
                ServiceCommand.Ignore("Remote shutter trigger ignored because address is missing")
            } else {
                ServiceCommand.TriggerRemoteShutter(address)
            }
        }

        if (action == SonyBluetoothConstants.ACTION_REQUEST_SHUTDOWN) {
            return if (address == null) {
                ServiceCommand.Ignore("Shutdown request ignored because address is missing")
            } else {
                ServiceCommand.Shutdown(address)
            }
        }

        return if (address == null) {
            ServiceCommand.ReconnectAlwaysOn
        } else {
            ServiceCommand.Connect(address)
        }
    }
}

