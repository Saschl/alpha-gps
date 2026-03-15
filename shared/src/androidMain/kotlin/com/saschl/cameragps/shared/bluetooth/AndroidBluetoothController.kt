package com.saschl.cameragps.shared.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class AndroidBluetoothController(
    context: Context,
) : BluetoothController {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val activeConnections = mutableMapOf<String, BluetoothGatt>()

    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices

    override val capabilities: Set<BluetoothCapability> = setOf(
        BluetoothCapability.Scan,
        BluetoothCapability.Connect,
        BluetoothCapability.ObserveConnection,
    )

    @SuppressLint("MissingPermission")
    override suspend fun startScan() {
        val bonded = adapter?.bondedDevices.orEmpty().map { device ->
            BluetoothDeviceInfo(
                identifier = device.address,
                name = device.name ?: "N/A",
                isConnected = activeConnections.containsKey(device.address),
            )
        }
        _devices.value = bonded
    }

    override suspend fun stopScan() {
        // Bonded-device scan is snapshot-based; nothing to stop.
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(identifier: String): Boolean {
        val device = runCatching { adapter?.getRemoteDevice(identifier) }.getOrNull() ?: return false
        if (activeConnections.containsKey(identifier)) return true

        val gatt = device.connectGatt(
            appContext,
            true,
            object : BluetoothGattCallback() {}
        ) ?: return false

        activeConnections[identifier] = gatt
        setConnectionState(identifier, true)
        return true
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect(identifier: String) {
        activeConnections.remove(identifier)?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        setConnectionState(identifier, false)
    }

    private fun setConnectionState(identifier: String, connected: Boolean) {
        _devices.update { current ->
            current.map { device ->
                if (device.identifier == identifier) {
                    device.copy(isConnected = connected)
                } else {
                    device
                }
            }
        }
    }
}
