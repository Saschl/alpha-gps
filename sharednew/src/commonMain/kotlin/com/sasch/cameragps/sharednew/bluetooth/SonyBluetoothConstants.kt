package com.sasch.cameragps.sharednew.bluetooth

object SonyBluetoothConstants {
    // Service UUID of the sony cameras
    val SERVICE_UUID = "8000dd00-dd00-ffff-ffff-ffffffffffff"

    val CONTROL_SERVICE_UUID = "8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF"

    // Characteristic for the location services
    val CHARACTERISTIC_UUID = "0000dd11-0000-1000-8000-00805f9b34fb"
    val CHARACTERISTIC_READ_UUID = "0000dd21-0000-1000-8000-00805f9b34fb"

    // needed for some cameras to enable the functionality
    val CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND = "0000dd30-0000-1000-8000-00805f9b34fb"
    val CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND = "0000dd31-0000-1000-8000-00805f9b34fb"

    val CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA = "0000dd01-0000-1000-8000-00805f9b34fb"

    val TIME_SYNC_CHARACTERISTIC_UUID = "0000cc13-0000-1000-8000-00805f9b34fb"

    const val ACTION_REQUEST_SHUTDOWN = "com.saschl.cameragps.ACTION_REQUEST_SHUTDOWN"

    // GPS enable command bytes
    val GPS_ENABLE_COMMAND = byteArrayOf(0x01)

    // Location update interval
    const val LOCATION_UPDATE_INTERVAL_MS = 10000L

    // Accuracy threshold for location updates
    const val ACCURACY_THRESHOLD_METERS = 200.0

    // Time threshold for old location updates (5 minutes)
    const val OLD_LOCATION_THRESHOLD_MS = 1000 * 60 * 5

    const val locationTransmissionNotificationId = 404
}