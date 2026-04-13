package com.saschl.cameragps.service

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.util.Collections

class CameraConnectionManager(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val gattCallback: BluetoothGattCallback
) {

    data class CameraConnectionConfig(
        val gatt: BluetoothGatt,
        val state: Int = -1,
        val writeCharacteristic: BluetoothGattCharacteristic? = null,
        val remoteControlCharacteristic: BluetoothGattCharacteristic? = null,
        val remoteControlDescriptor: BluetoothGattDescriptor? = null,
        val remoteStatusCharacteristic: BluetoothGattCharacteristic? = null,
        val remoteStatusDescriptor: BluetoothGattDescriptor? = null,
        val remoteFeatureActive: Boolean = false,
        var locationDataConfig: LocationDataConfig = LocationDataConfig(shouldSendTimeZoneAndDst = true)
    )

    private val connections =
        Collections.synchronizedMap(mutableMapOf<String, CameraConnectionConfig>())

    fun connect(mac: String): Boolean {
        // Check if already connected
        if (connections.containsKey(mac)) {
            return true
        }

        try {
            val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(mac)
            if (device.bondState != BOND_BONDED) {
                Timber.e("Device $mac is not paired. Cannot connect.")
                return false
            }
            val gatt = device.connectGatt(context, true, gattCallback)
                ?: throw IllegalStateException("Failed to connect to device $mac: GATT is null")
            connections[mac] = CameraConnectionConfig(gatt = gatt)
        } catch (e: SecurityException) {
            Timber.e("SecurityException while connecting to device $mac: ${e.message}")
            return false
        }

        return true
    }


    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectAll() {
        val snapshot: List<CameraConnectionConfig> = synchronized(connections) {
            connections.values.toList().also { connections.clear() }
        }
        snapshot.forEach { config ->
            runCatching {
                config.gatt.disconnect()
                config.gatt.close()
            }.onFailure { Timber.w(it) }
        }
    }

    fun isConnected(config: String): Boolean {
        return connections.containsKey(config)
    }

    fun getActiveCameras(): Set<String> {
        return connections.filter { it.value.state == BluetoothGatt.GATT_SUCCESS }.keys.toSet()
    }

    fun pauseDevice(address: String) {
        connections[address]?.let { config ->
            connections[address] = config.copy(state = BluetoothGatt.GATT_FAILURE)
        }

    }

    fun resumeDevice(address: String) {
        connections[address]?.let { config ->
            connections[address] = config.copy(state = BluetoothGatt.GATT_SUCCESS)
        }
    }

    fun getActiveConnections(): Collection<CameraConnectionConfig> {
        return connections.values.filter { it.state == BluetoothGatt.GATT_SUCCESS }.toList()
    }

    fun getConnection(address: String): CameraConnectionConfig? {
        return connections[address]
    }

    fun setWriteCharacteristic(
        address: String,
        writeLocationCharacteristic: BluetoothGattCharacteristic?
    ) {
        connections[address]?.let { config ->
            connections[address] = config.copy(writeCharacteristic = writeLocationCharacteristic)
        }
    }

    fun setLocationDataConfig(uppercase: String, locationDataConfig: LocationDataConfig) {
        connections[uppercase]?.let { config ->
            connections[uppercase] = config.copy(locationDataConfig = locationDataConfig)
        }
    }

    fun setRemoteControlCharacteristic(
        address: String,
        remoteControlCharacteristic: BluetoothGattCharacteristic?
    ) {
        connections[address]?.let { config ->
            connections[address] =
                config.copy(remoteControlCharacteristic = remoteControlCharacteristic)
        }
    }

    fun setRemoteControlDescriptor(
        address: String,
        remoteControlDescriptor: BluetoothGattDescriptor?
    ) {
        connections[address]?.let { config ->
            connections[address] = config.copy(remoteControlDescriptor = remoteControlDescriptor)
        }
    }

    fun setRemoteStatusCharacteristic(
        address: String,
        remoteStatusCharacteristic: BluetoothGattCharacteristic?
    ) {
        connections[address]?.let { config ->
            connections[address] =
                config.copy(remoteStatusCharacteristic = remoteStatusCharacteristic)
        }
    }

    fun setRemoteStatusDescriptor(
        address: String,
        remoteStatusDescriptor: BluetoothGattDescriptor?
    ) {
        connections[address]?.let { config ->
            connections[address] = config.copy(remoteStatusDescriptor = remoteStatusDescriptor)
        }
    }

    fun setRemoteFeatureActive(address: String, isActive: Boolean) {
        connections[address]?.let { config ->
            connections[address] = config.copy(remoteFeatureActive = isActive)
        }
    }
}