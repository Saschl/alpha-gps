package com.sasch.cameragps.sharednew.database.logging

import com.diamondedge.logging.Logger
import com.diamondedge.logging.VariableLogLevel
import kotlin.time.Clock

class DatabaseLogger(
    private val logRepository: LogRepository,
    private val logController: VariableLogLevel
) : Logger {
    override fun verbose(tag: String, msg: String) {
        logRepository.insertLog(
            timestamp = Clock.System.now().toEpochMilliseconds(),
            priority = 2,
            tag = tag,
            message = msg,
            exception = null
        )
    }

    override fun debug(tag: String, msg: String) {
        logRepository.insertLog(
            timestamp = Clock.System.now().toEpochMilliseconds(),
            priority = 3,
            tag = tag,
            message = msg,
            exception = null
        )
    }

    override fun info(tag: String, msg: String) {
        logRepository.insertLog(
            timestamp = Clock.System.now().toEpochMilliseconds(),
            priority = 4,
            tag = tag,
            message = msg,
            exception = null
        )
    }

    override fun warn(tag: String, msg: String, t: Throwable?) {
        logRepository.insertLog(
            timestamp = Clock.System.now().toEpochMilliseconds(),
            priority = 5,
            tag = tag,
            message = msg,
            exception = null
        )
    }

    override fun error(tag: String, msg: String, t: Throwable?) {
        logRepository.insertLog(
            timestamp = Clock.System.now().toEpochMilliseconds(),
            priority = 6,
            tag = tag,
            message = msg,
            exception = null
        )
    }

    override fun isLoggingVerbose(): Boolean = logController.isLoggingVerbose()

    override fun isLoggingDebug(): Boolean = logController.isLoggingDebug()

    override fun isLoggingInfo(): Boolean = logController.isLoggingInfo()

    override fun isLoggingWarning(): Boolean = logController.isLoggingWarning()

    override fun isLoggingError(): Boolean = logController.isLoggingError()

}
