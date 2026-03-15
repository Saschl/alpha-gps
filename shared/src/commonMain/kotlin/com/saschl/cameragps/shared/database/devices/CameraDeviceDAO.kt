package com.saschl.cameragps.shared.database.devices

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.IGNORE
import androidx.room.Query

@Dao
interface CameraDeviceDAO {

    @Query("SELECT * FROM camera_devices")
    suspend fun getAllCameraDevices(): List<CameraDevice>

    @Insert(onConflict = IGNORE)
    suspend fun insertDevice(device: CameraDevice)

    @Delete
    suspend fun deleteDevice(device: CameraDevice)

    @Query("UPDATE camera_devices SET deviceEnabled = :enabled WHERE mac = UPPER(:deviceId)")
    suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean)

    @Query("SELECT alwaysOnEnabled FROM camera_devices WHERE mac = UPPER(:address)")
    suspend fun isDeviceAlwaysOnEnabled(address: String): Boolean

    @Query("UPDATE camera_devices SET alwaysOnEnabled = :enabled WHERE mac = UPPER(:deviceId)")
    suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean)

    @Query("SELECT deviceEnabled FROM camera_devices WHERE mac = UPPER(:address)")
    suspend fun isDeviceEnabled(address: String): Boolean

    @Query("SELECT count(1) FROM camera_devices WHERE alwaysOnEnabled = 1")
    suspend fun getAlwaysOnEnabledDeviceCount(): Int
}

