package com.sasch.cameragps.sharednew.bluetooth

import kotlinx.coroutines.flow.StateFlow

interface BluetoothController {
    val devices: StateFlow<List<BluetoothDeviceInfo>>

    val capabilities: Set<BluetoothCapability>

    suspend fun startScan()

    suspend fun stopScan()

    suspend fun connect(identifier: String): Boolean

    suspend fun disconnect(identifier: String)
}

