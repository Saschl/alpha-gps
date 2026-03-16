package com.sasch.cameragps.sharednew.bluetooth

data class BluetoothDeviceInfo(
    val identifier: String,
    val name: String,
    val isConnected: Boolean,
)

enum class BluetoothCapability {
    Scan,
    Connect,
    ObserveConnection,
}

