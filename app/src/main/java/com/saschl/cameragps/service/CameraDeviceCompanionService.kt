package com.saschl.cameragps.service

import android.Manifest
import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber
import java.util.Locale


@RequiresApi(Build.VERSION_CODES.S)
class CameraDeviceCompanionService : CompanionDeviceService() {

    private fun startLocationSenderService(address: String?) {
        if (PreferencesManager.isAppEnabled(this)) {
            if (!LocationSenderService.hasLocationPermission(this)) {
                Timber.e("Location permission missing, not starting LocationSenderService for $address")
                return
            }

            val serviceIntent = Intent(this, LocationSenderService::class.java)
            serviceIntent.putExtra("address", address?.uppercase(Locale.getDefault()))
            Timber.i("Starting LocationSenderService for address: $address")

            startForegroundService(serviceIntent)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || missingPermissions()) {
            return
        }
        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Cannot post notifications, App may not work as expected")
        }
        Timber.i("Device appeared oldest API: $address")

        startLocationSenderService(address)
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingPermission")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Timber.i("Device appeared old API: ${associationInfo.id}")

            val address = associationInfo.deviceMacAddress?.toString() ?: return

            startLocationSenderService(address)
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    @SuppressLint("MissingPermission")
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        super.onDevicePresenceEvent(event)

        Timber.i(
            "CDM presence event: ${presenceEventName(event.event)} (${event.event}), association=${event.associationId}"
        )

        // when bluetooth is not permitted, we're done
        if (missingPermissions()) {
            Timber.e("Missing bluetooth permissions in  ${CameraDeviceCompanionService::class.java}")
            return
        }

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Cannot post notifications, App may not work as expected")
        }

        val associationId = event.associationId
        val deviceManager = getSystemService<CompanionDeviceManager>()
        val associatedDevices = deviceManager?.getMyAssociations()
        val associationInfo = associatedDevices?.find { it.id == associationId }
        val address = associationInfo?.deviceMacAddress?.toString()

        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) {
            startLocationSenderService(address)
        }

        if (event.event == DevicePresenceEvent.EVENT_BLE_DISAPPEARED) {
            if (address == null) {
                Timber.e("Could not get address for disappeared device with association id: $associationId")
                return
            }
            reportDeviceDisappeared(address)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Timber.i("Device disappeared oldest API: $address")
            reportDeviceDisappeared(address)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            super.onDeviceDisappeared(associationInfo)
            Timber.i("Device disappeared old API: ${associationInfo.id}")
            val address = associationInfo.deviceMacAddress?.toString() ?: return
            reportDeviceDisappeared(address)
        }
    }

    private fun reportDeviceDisappeared(address: String) {
        // startService from the background throws when the target service is not
        // already running — and if it is not running there is nothing to shut
        // down anyway. isRunning can flip between check and call, so keep both.
        if (!LocationSenderService.isRunning) {
            Timber.i("LocationSenderService not running, ignoring disappearance of $address")
            return
        }
        val presenceIntent = Intent(this, LocationSenderService::class.java).apply {
            action = ServiceCommandRouter.ACTION_DEVICE_DISAPPEARED
        }
        presenceIntent.putExtra("address", address.uppercase())
        try {
            startService(presenceIntent)
        } catch (e: IllegalStateException) {
            Timber.w(e, "Could not deliver disappearance intent for $address")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("CDM started")
    }


    override fun onUnbind(intent: Intent?): Boolean {
        Timber.i("CompanionDeviceService unbound; foreground service follows GATT connection state")
        return super.onUnbind(intent)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun presenceEventName(event: Int): String = when (event) {
        DevicePresenceEvent.EVENT_BLE_APPEARED -> "BLE_APPEARED"
        DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> "BLE_DISAPPEARED"
        DevicePresenceEvent.EVENT_BT_CONNECTED -> "BT_CONNECTED"
        DevicePresenceEvent.EVENT_BT_DISCONNECTED -> "BT_DISCONNECTED"
        DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED -> "SELF_MANAGED_APPEARED"
        DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED -> "SELF_MANAGED_DISAPPEARED"
        else -> "UNKNOWN"
    }


    /**
     * Check BLUETOOTH_CONNECT is granted and POST_NOTIFICATIONS is granted for devices running
     * Android 13 and above.
     */
    private fun missingPermissions(): Boolean = ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) != PackageManager.PERMISSION_GRANTED

}
