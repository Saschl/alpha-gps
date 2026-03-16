package com.saschl.cameragps.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.database.logging.LogDao
import com.sasch.cameragps.sharednew.database.logging.LogEntry

@Database(
    entities = [LogEntry::class, CameraDevice::class],
    version = 3,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, LogDatabase.DeleteOldColumn::class)
    ]
)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun cameraDeviceDao(): CameraDeviceDAO

    companion object {
        @Volatile
        private var INSTANCE: LogDatabase? = null

        fun getDatabase(context: Context): LogDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LogDatabase::class.java,
                    "log_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }


    @DeleteColumn(
        tableName = "camera_devices",
        columnName = "transmitTimezoneAndDst"
    )
    class DeleteOldColumn : AutoMigrationSpec
}