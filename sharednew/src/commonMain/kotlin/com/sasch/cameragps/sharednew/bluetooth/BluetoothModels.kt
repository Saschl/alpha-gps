package com.sasch.cameragps.sharednew.bluetooth

data class BluetoothDeviceInfo(
    val identifier: String,
    val name: String,
    val isConnected: Boolean,
    val isSaved: Boolean = false,
    val isTransmissionActive: Boolean = false,
    val isRemoteFeatureActive: Boolean = false,
)

enum class BluetoothCapability {
    Scan,
    Connect,
    ObserveConnection,
}

