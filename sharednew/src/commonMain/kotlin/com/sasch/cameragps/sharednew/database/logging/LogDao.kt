package com.sasch.cameragps.sharednew.database.logging

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insertLog(logEntry: LogEntry)

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 200): Flow<List<LogEntry>>

    @Query("DELETE FROM log_entries WHERE id NOT IN (SELECT id FROM log_entries ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun deleteOldLogs(keepCount: Int = 500)

    @Query("DELETE FROM log_entries")
    suspend fun clearAllLogs()

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun getLogCount(): Int
}

