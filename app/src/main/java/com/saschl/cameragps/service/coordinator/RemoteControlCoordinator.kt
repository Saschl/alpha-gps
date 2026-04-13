package com.saschl.cameragps.service.coordinator

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Handler
import android.os.Looper
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.saschl.cameragps.service.BluetoothGattUtils
import com.saschl.cameragps.service.CameraConnectionManager
import com.saschl.cameragps.service.ServiceEvent
import com.saschl.cameragps.service.ServiceEventBus
import timber.log.Timber

class RemoteControlCoordinator(
    private val cameraConnectionManager: CameraConnectionManager,
    private val eventBus: ServiceEventBus,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val activeProbeRunnables = mutableMapOf<String, Runnable>()

    companion object {
        private const val REMOTE_STATUS_PROBE_INTERVAL_MS = 3_000L
        private const val REMOTE_STATUS_PROBE_INITIAL_DELAY_MS = 500L
    }

    @SuppressLint("MissingPermission")
    fun handleRemoteShutterRequest(address: String): Boolean {
        val normalizedAddress = address.uppercase()
        val connection = cameraConnectionManager.getConnection(normalizedAddress)

        if (connection == null || connection.state != BluetoothGatt.GATT_SUCCESS) {
            Timber.w("Remote shutter requested for $normalizedAddress but no active connection is available")
            return false
        }

        if (!connection.remoteFeatureActive) {
            Timber.w("Remote shutter requested for $normalizedAddress but remote mode is inactive on camera")
            return false
        }

        val wroteDown = writeRemoteShutterCommand(
            gatt = connection.gatt,
            characteristic = connection.remoteControlCharacteristic,
            command = SonyBluetoothConstants.FULL_SHUTTER_DOWN_COMMAND,
        )

        if (!wroteDown) {
            Timber.w("Remote shutter down command failed for $normalizedAddress")
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun subscribeToRemoteStatusUpdates(
        gatt: BluetoothGatt,
        remoteStatusCharacteristic: BluetoothGattCharacteristic?,
        remoteStatusDescriptor: BluetoothGattDescriptor?,
    ) {
        if (remoteStatusCharacteristic == null) {
            Timber.i("Remote status characteristic not found for ${gatt.device.address}")
            return
        }

        val notifyEnabled = gatt.setCharacteristicNotification(remoteStatusCharacteristic, true)
        if (!notifyEnabled) {
            Timber.w("Failed to enable local notifications for remote status on ${gatt.device.address}")
            return
        }

        if (remoteStatusDescriptor == null) {
            Timber.w("CCCD descriptor missing for remote status on ${gatt.device.address}")
            return
        }

        val subscribed = BluetoothGattUtils.writeDescriptor(
            gatt,
            remoteStatusDescriptor,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
        )

        if (subscribed) {
            Timber.i("Subscribed to remote status notifications for ${gatt.device.address}")
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerRemoteStatusProbe(gatt: BluetoothGatt) {
        val address = gatt.device.address.uppercase()
        val connection = cameraConnectionManager.getConnection(address)

        subscribeToRemoteStatusUpdates(
            gatt = gatt,
            remoteStatusCharacteristic = connection?.remoteStatusCharacteristic,
            remoteStatusDescriptor = connection?.remoteStatusDescriptor,
        )

        if (connection?.remoteControlCharacteristic == null) {
            Timber.i("Skipping remote status probe for $address because no remote control characteristic is available")
            return
        }

        // Cancel any existing probe loop for this address before starting a new one
        cancelRemoteStatusProbe(address)

        val probeRunnable = object : Runnable {
            override fun run() {
                val currentConnection = cameraConnectionManager.getConnection(address)
                if (currentConnection == null || currentConnection.state != BluetoothGatt.GATT_SUCCESS) {
                    Timber.i("Connection no longer active for $address, stopping remote status probe")
                    cancelRemoteStatusProbe(address)
                    return
                }

                val characteristic = currentConnection.remoteControlCharacteristic
                if (characteristic == null) {
                    Timber.w("Remote control characteristic gone for $address, stopping remote status probe")
                    cancelRemoteStatusProbe(address)
                    return
                }

                val probeSent = BluetoothGattUtils.writeCharacteristic(
                    currentConnection.gatt,
                    characteristic,
                    SonyBluetoothConstants.PROBE_COMMAND,
                )
                if (probeSent) {
                    Timber.i("Sent remote status probe command for $address")
                } else {
                    Timber.w("Failed to send remote status probe command for $address")
                }

                handler.postDelayed(this, REMOTE_STATUS_PROBE_INTERVAL_MS)
            }
        }

        activeProbeRunnables[address] = probeRunnable
        handler.postDelayed(probeRunnable, REMOTE_STATUS_PROBE_INITIAL_DELAY_MS)
        Timber.i("Started periodic remote status probe for $address (every ${REMOTE_STATUS_PROBE_INTERVAL_MS}ms)")
    }

    fun cancelRemoteStatusProbe(address: String) {
        activeProbeRunnables.remove(address.uppercase())?.let { runnable ->
            handler.removeCallbacks(runnable)
            Timber.i("Cancelled remote status probe for ${address.uppercase()}")
        }
    }

    fun cancelAllProbes() {
        activeProbeRunnables.forEach { (address, runnable) ->
            handler.removeCallbacks(runnable)
            Timber.i("Cancelled remote status probe for $address")
        }
        activeProbeRunnables.clear()
    }

    fun handleRemoteStatusCharacteristicChanged(
        gatt: BluetoothGatt,
        value: ByteArray,
    ): Boolean {
        val isActive = isRemoteFeatureActive(value)
        val address = gatt.device.address.uppercase()
        cameraConnectionManager.setRemoteFeatureActive(address, isActive)
        Timber.i(
            "Remote feature status update for ${gatt.device.address}: active=$isActive value=${
                value.joinToString(
                    ","
                )
            }"
        )
        return value.contentEquals(byteArrayOf(0x02, 0xA0.toByte(), 0x00))
    }

    @SuppressLint("MissingPermission")
    fun writeRemoteShutterUpIfSupported(gatt: BluetoothGatt): Boolean {
        val connection =
            cameraConnectionManager.getConnection(gatt.device.address.uppercase()) ?: return false
        return writeRemoteShutterCommand(
            gatt = connection.gatt,
            characteristic = connection.remoteControlCharacteristic,
            command = SonyBluetoothConstants.FULL_SHUTTER_UP_COMMAND,
        )
    }

    @SuppressLint("MissingPermission")
    private fun writeRemoteShutterCommand(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic?,
        command: ByteArray,
    ): Boolean {
        characteristic?.let {
            if (BluetoothGattUtils.writeCharacteristic(gatt, it, command)) {
                return true
            }
        }
        Timber.w("No writable remote control endpoint discovered for ${gatt.device.address}")
        return false
    }

    fun handleRemoteStatusCharacteristicWriteResponse(
        gatt: BluetoothGatt,
        success: Boolean,
    ) {
        if (success) {
            eventBus.emit(
                ServiceEvent.RemoteFeatureActivated(
                    address = gatt.device.address.uppercase(),

                    )
            )
        } else {
            eventBus.emit(
                ServiceEvent.RemoteFeatureDeactivated(
                    address = gatt.device.address.uppercase(),
                )
            )
        }

    }

    private fun isRemoteFeatureActive(value: ByteArray): Boolean {
        if (value.isEmpty()) return false
        return !value.contentEquals(byteArrayOf(0x02, 0xC3.toByte(), 0x00))
    }
}
