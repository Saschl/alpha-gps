package com.sasch.cameragps.sharednew.logging

import com.sasch.cameragps.sharednew.database.logging.LogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

class IosLogFormatter(private val logRepository: LogRepository) : LogFormatter {
    override fun format(): Flow<List<String>> {
        return logRepository.getRecentLogs().map { entries ->
            entries.map { entry ->
                val date = formatTimestamp(entry.timestamp)
                "[$date] [${priorityToString(entry.priority)}] ${entry.tag ?: "App"}: ${entry.message}" +
                        (entry.exception?.let { "\n$it" } ?: "")
            }
        }
    }

    private fun priorityToString(priority: Int): String = when (priority) {
        1, 2 -> "V"
        3 -> "D"
        4 -> "I"
        5 -> "W"
        6 -> "E"
        7 -> "A"
        else -> priority.toString()
    }

    private fun formatTimestamp(timestamp: Long): String {
        val formatter = NSDateFormatter().apply {
            dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        }
        val date = NSDate.dateWithTimeIntervalSince1970(timestamp.toDouble() / 1000.0)
        return formatter.stringFromDate(date)
    }
}



