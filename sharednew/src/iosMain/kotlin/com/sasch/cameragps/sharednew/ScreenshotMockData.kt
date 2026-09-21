package com.sasch.cameragps.sharednew

import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListItem

/**
 * Toggle this flag to true before taking App Store screenshots,
 * then set it back to false before submitting the build.
 */
internal const val SCREENSHOT_MODE = false

internal val mockDevices = listOf(
    // Saved & connected — appears in the "Saved Devices" section with a green status
    BluetoothDeviceInfo(
        identifier = "ILCE-7M4",
        name = "ILCE-7M4",
        isConnected = true,
        isSaved = true,
    ),
    // Saved but not currently connected
    BluetoothDeviceInfo(
        identifier = "ILCE-6700",
        name = "ILCE-6700",
        isConnected = false,
        isSaved = true,
    ),
    BluetoothDeviceInfo(
        identifier = "ILCE-7RM5",
        name = "ILCE-7RM5",
        isConnected = false,
        isSaved = true,
    ),
    BluetoothDeviceInfo(
        identifier = "ILCE-7CM2",
        name = "ILCE-7CM2",
        isConnected = false,
        isSaved = true,
    ),
    BluetoothDeviceInfo(
        identifier = "ILCE-7SM3",
        name = "ILCE-7SM3",
        isConnected = false,
        isSaved = true,
    ),
    BluetoothDeviceInfo(
        identifier = "ILCE-1",
        name = "ILCE-1",
        isConnected = false,
        isSaved = true,
    ),
    // Nearby (not yet saved) — appears in the "Nearby Cameras" section
    BluetoothDeviceInfo(
        identifier = "ZV-E10M2",
        name = "ZV-E10M2",
        isConnected = false,
        isSaved = false,
    ),
    BluetoothDeviceInfo(
        identifier = "ILCE-9M3",
        name = "ILCE-9M3",
        isConnected = false,
        isSaved = false,
    ),
    BluetoothDeviceInfo(
        identifier = "ZV-E1",
        name = "ZV-E1",
        isConnected = false,
        isSaved = false,
    ),
    BluetoothDeviceInfo(
        identifier = "ILCE-7M3",
        name = "ILCE-7M3",
        isConnected = false,
        isSaved = false,
    ),
    BluetoothDeviceInfo(
        identifier = "ILCE-6400",
        name = "ILCE-6400",
        isConnected = false,
        isSaved = false,
    ),
    BluetoothDeviceInfo(
        identifier = "DSC-RX100M7",
        name = "DSC-RX100M7",
        isConnected = false,
        isSaved = false,
    ),
    BluetoothDeviceInfo(
        identifier = "ZV-1M2",
        name = "ZV-1M2",
        isConnected = false,
        isSaved = false,
    ),
    BluetoothDeviceInfo(
        identifier = "ILCE-7CR",
        name = "ILCE-7CR",
        isConnected = false,
        isSaved = false,
    ),
)

/**
 * Per-row state for the mock devices: the connected camera is fully active
 * (transmitting + remote feature on) so screenshots show the green status
 * and the large shutter button. Devices without an entry render all-off.
 */
internal val mockDeviceListItems = mapOf(
    "ILCE-7M4" to DeviceListItem(
        identifier = "ILCE-7M4",
        customName = null,
        isAlwaysOnEnabled = false,
        isTransmissionActive = true,
        isRemoteFeatureActive = true,
        isShutterActive = false,
    ),
)
