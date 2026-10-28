package com.sasch.cameragps.sharednew.database.logging

import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.VariableLogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LogRepositoryTest {
    @Test
    fun keepsFiveThousandEntriesThenTrimsToTheNewestFourThousand() = runTest {
        val dao = FakeLogDao(initialCount = 4_999)
        val repository = LogRepository(dao, this)

        repository.append(5_000)
        advanceUntilIdle()
        assertEquals(5_000, dao.getLogCount())
        assertEquals(0, dao.trimCount)

        repository.append(5_001)
        advanceUntilIdle()
        assertEquals(4_000, dao.getLogCount())
        assertEquals(1, dao.trimCount)
        assertEquals(5_001L, dao.getAllLogs().first().first().timestamp)
        assertEquals(1_002L, dao.getAllLogs().first().last().timestamp)

        for (timestamp in 5_002L..6_001L) {
            repository.append(timestamp)
        }
        advanceUntilIdle()
        assertEquals(5_000, dao.getLogCount())
        assertEquals(1, dao.trimCount)

        repository.append(6_002)
        advanceUntilIdle()
        assertEquals(4_000, dao.getLogCount())
        assertEquals(2, dao.trimCount)
    }

    @Test
    fun viewerOnlyReceivesTheNewestTwoHundredWithoutDeletingHistory() = runTest {
        val dao = FakeLogDao(initialCount = 5_000)
        val repository = LogRepository(dao, this)

        val displayed = repository.getRecentLogs().first()

        assertEquals(200, displayed.size)
        assertEquals(5_000L, displayed.first().timestamp)
        assertEquals(4_801L, displayed.last().timestamp)
        assertEquals(5_000, dao.getLogCount())
    }

    @Test
    fun clearRemovesAllRetainedLogsIncludingThoseOutsideTheViewer() = runTest {
        val dao = FakeLogDao(initialCount = 5_000)
        val repository = LogRepository(dao, this)

        repository.clearAllLogs()

        assertEquals(0, dao.getLogCount())
        assertEquals(emptyList(), repository.getRecentLogs().first())
    }

    @Test
    fun exportIncludesAllRetainedHistoryInChronologicalOrder() = runTest {
        val repository = LogRepository(FakeLogDao(initialCount = 5_000), this)

        val exported = repository.getLogsForExport()

        assertEquals(5_000, exported.size)
        assertEquals((1L..5_000L).toList(), exported.map { it.timestamp })
    }

    @Test
    fun sharedLoggerPreservesWarningAndErrorStackTraces() = runTest {
        val repository = LogRepository(FakeLogDao(initialCount = 0), this)
        val logger = DatabaseLogger(repository, VariableLogLevel(LogLevel.Info))
        logger.warn("BLE", "Retrying", IllegalStateException("Pairing failed"))
        logger.error("BLE", "Stopped", IllegalArgumentException("Invalid packet"))
        advanceUntilIdle()

        val logs = repository.getLogsForExport().associateBy { it.message }
        assertTrue(logs.getValue("Retrying").exception.orEmpty().contains("Pairing failed"))
        assertTrue(logs.getValue("Stopped").exception.orEmpty().contains("Invalid packet"))
    }

    private fun LogRepository.append(timestamp: Long) {
        insertLog(
            timestamp,
            priority = 4,
            tag = "Test",
            message = "Log $timestamp",
            exception = null
        )
    }

    private class FakeLogDao(initialCount: Int) : LogDao {
        private val entries = MutableStateFlow((initialCount downTo 1).map {
            LogEntry(it.toLong(), it.toLong(), 4, "Test", "Log $it", null)
        })
        var trimCount = 0
            private set

        override suspend fun insertLog(logEntry: LogEntry) {
            entries.value = (entries.value + logEntry).sortedByDescending { it.timestamp }
        }

        override fun getAllLogs(): Flow<List<LogEntry>> = entries

        override fun getRecentLogs(limit: Int): Flow<List<LogEntry>> =
            entries.map { it.take(limit) }

        override suspend fun deleteOldLogs(keepCount: Int) {
            trimCount++
            entries.value = entries.value.take(keepCount)
        }

        override suspend fun clearAllLogs() {
            entries.value = emptyList()
        }

        override suspend fun getLogCount(): Int = entries.value.size
    }
}
