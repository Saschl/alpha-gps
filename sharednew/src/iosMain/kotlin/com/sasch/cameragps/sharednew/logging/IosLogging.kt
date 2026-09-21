package com.sasch.cameragps.sharednew.logging

import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.VariableLogLevel
import com.sasch.cameragps.sharednew.crash.IosCrashReporting
import com.sasch.cameragps.sharednew.database.logging.DatabaseLogger
import com.sasch.cameragps.sharednew.database.logging.LogRepository

/**
 * Installs the iOS logger set.
 *
 * `KmLogging.setLoggers` **replaces** the whole logger list, so calling it
 * directly anywhere else silently detaches Sentry from the log stream until the
 * next launch. Every reconfiguration (launch, log-level change) goes through
 * here instead.
 */
internal object IosLogging {

    /**
     * Log to the in-app database at [level] plus, once the user opted into
     * crash reporting, to Sentry.
     *
     * The database log level is the user's choice; the Sentry logger keeps its
     * own fixed thresholds, so turning the log level down does not quietly stop
     * error reporting (and turning it up does not start shipping verbose lines).
     */
    fun install(logRepository: LogRepository, level: LogLevel) {
        val database = DatabaseLogger(logRepository, VariableLogLevel(level))
        val sentry = IosCrashReporting.activeLogger()
        if (sentry == null) {
            KmLogging.setLoggers(database)
        } else {
            KmLogging.setLoggers(database, sentry)
        }
    }
}
