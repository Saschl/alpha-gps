package com.sasch.cameragps.sharednew.database.logging

import androidx.room.RoomDatabase
import com.sasch.cameragps.sharednew.database.LogDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class LogRepository(databaseBuilder: RoomDatabase.Builder<LogDatabase>) {
    private val logDao = LogDatabase.getRoomDatabase(databaseBuilder).logDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun insertLog(
        timestamp: Long,
        priority: Int,
        tag: String?,
        message: String,
        exception: String?
    ) {
        scope.launch {
            val logEntry = LogEntry(
                timestamp = timestamp,
                priority = priority,
                tag = tag,
                message = message,
                exception = exception
            )
            logDao.insertLog(logEntry)

            // Clean up old logs to prevent database from growing too large
            val count = logDao.getLogCount()
            if (count > 1000) {
                logDao.deleteOldLogs(500) // Keep only latest 500 logs
            }
        }
    }

    fun getRecentLogs(limit: Int = 200): Flow<List<LogEntry>> = logDao.getRecentLogs(limit)

    suspend fun clearAllLogs() {
        logDao.clearAllLogs()
    }
}