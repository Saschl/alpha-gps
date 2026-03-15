package com.saschl.cameragps.shared.database.devices

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "camera_devices")
data class CameraDevice(
    @PrimaryKey(autoGenerate = false)
    val mac: String,
    val deviceEnabled: Boolean = true,
    val alwaysOnEnabled: Boolean = false,
    val deviceName: String = "N/A",
)

