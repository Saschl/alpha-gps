package com.saschl.cameragps.shared.bluetooth

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

