package com.sasch.cameragps.sharednew.database.logging

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val priority: Int,
    val tag: String?,
    val message: String,
    val exception: String?,
)

