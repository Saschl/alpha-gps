package com.sasch.cameragps.sharednew.database.devices

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "camera_devices")
data class CameraDevice(
    @PrimaryKey(autoGenerate = false)
    val mac: String,
    @ColumnInfo(defaultValue = "1")
    val deviceEnabled: Boolean = true,
    val alwaysOnEnabled: Boolean = false,
    val deviceName: String = "N/A",
    /**
     * True once a person named this camera, either in the app or through the
     * iOS system rename sheet. A custom name is never replaced by a hardware
     * name; a derived one is upgraded as soon as a better hardware name appears.
     */
    @ColumnInfo(defaultValue = "0")
    val deviceNameIsCustom: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val remoteControlEnabled: Boolean = false,
)
