package com.sasch.cameragps.sharednew.database.logging

import androidx.room.RoomDatabase
import com.sasch.cameragps.sharednew.database.LogDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LogRepository internal constructor(
    private val logDao: LogDao,
    private val scope: CoroutineScope,
) {
    constructor(databaseBuilder: RoomDatabase.Builder<LogDatabase>) : this(
        LogDatabase.getRoomDatabase(databaseBuilder).logDao(),
        CoroutineScope(Dispatchers.IO),
    )

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
            if (count > MAX_RETAINED_LOGS) {
                logDao.deleteOldLogs(RETAINED_LOGS_AFTER_TRIM)
            }
        }
    }

    fun getRecentLogs(limit: Int = 200): Flow<List<LogEntry>> = logDao.getRecentLogs(limit)

    /** A chronological snapshot of all retained logs, independent of the viewer limit. */
    suspend fun getLogsForExport(): List<LogEntry> = logDao.getAllLogs().first().reversed()

    suspend fun clearAllLogs() {
        logDao.clearAllLogs()
    }

    private companion object {
        const val MAX_RETAINED_LOGS = 5_000
        const val RETAINED_LOGS_AFTER_TRIM = 4_000
    }
}
