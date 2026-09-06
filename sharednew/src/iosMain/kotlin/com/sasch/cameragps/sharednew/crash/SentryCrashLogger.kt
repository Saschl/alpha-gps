package com.sasch.cameragps.sharednew.crash

import com.diamondedge.logging.Logger
import io.sentry.kotlin.multiplatform.Scope
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.log.SentryLogLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb

/**
 * Feeds the shared `KmLogging` stream into Sentry, the way
 * `SentryTimberIntegration` does on Android.
 *
 * The thresholds are not duplicated here — [CrashReportPolicy.route] owns them
 * for both platforms — and every message is passed through
 * [CrashReportPolicy.redact] so BLE addresses stay on the device.
 *
 * Installed only while [IosCrashReporting.isStarted]; `KmLogging` calls loggers
 * from whichever thread logged, so this class keeps no mutable state.
 */
internal class SentryCrashLogger : Logger {

    override fun verbose(tag: String, msg: String) =
        report(CrashReportPolicy.PRIORITY_VERBOSE, tag, msg, null)

    override fun debug(tag: String, msg: String) =
        report(CrashReportPolicy.PRIORITY_DEBUG, tag, msg, null)

    override fun info(tag: String, msg: String) =
        report(CrashReportPolicy.PRIORITY_INFO, tag, msg, null)

    override fun warn(tag: String, msg: String, t: Throwable?) =
        report(CrashReportPolicy.PRIORITY_WARN, tag, msg, t)

    override fun error(tag: String, msg: String, t: Throwable?) =
        report(CrashReportPolicy.PRIORITY_ERROR, tag, msg, t)

    // Verbose and debug are dropped by CrashReportPolicy anyway; reporting them
    // as disabled keeps KmLogging from calling this logger at all for them.
    override fun isLoggingVerbose(): Boolean = false

    override fun isLoggingDebug(): Boolean = false

    override fun isLoggingInfo(): Boolean = true

    override fun isLoggingWarning(): Boolean = true

    override fun isLoggingError(): Boolean = true

    private fun report(priority: Int, tag: String, msg: String, t: Throwable?) {
        val message = CrashReportPolicy.redact(msg)

        // Structured logs are orthogonal to the routing below: an INFO line is a
        // breadcrumb AND a log entry, an ERROR is an issue AND a log entry. This
        // is what fills Sentry's Logs view, which breadcrumbs never reach unless
        // something actually crashes.
        if (CrashReportPolicy.shouldSendAsLog(priority)) {
            sendStructuredLog(priority, tag, message)
        }

        when (CrashReportPolicy.route(priority)) {
            CrashReportRoute.Ignore -> Unit

            CrashReportRoute.Breadcrumb -> Sentry.addBreadcrumb(
                Breadcrumb(
                    level = priority.toSentryLevel(),
                    type = "debug",
                    message = message,
                    // KmLogging only fills the tag when one of the installed
                    // loggers is a TagProvider, which none of ours is — an
                    // empty category is worse than none in the Sentry UI.
                    category = tag.ifBlank { null },
                )
            )

            // A throwable carries the Kotlin stack trace, so prefer it as the
            // event body and keep the log line as context. Without one there is
            // nothing but the message to group on.
            CrashReportRoute.Event -> {
                val decorate: (Scope) -> Unit = { scope ->
                    scope.level = priority.toSentryLevel()
                    if (tag.isNotBlank()) scope.setTag("logger", tag)
                }
                if (t != null) {
                    Sentry.captureException(t) { scope ->
                        decorate(scope)
                        scope.setExtra("log_message", message)
                    }
                } else {
                    Sentry.captureMessage(message, decorate)
                }
            }
        }
    }

    private fun sendStructuredLog(priority: Int, tag: String, message: String) {
        Sentry.logger.log(priority.toSentryLogLevel()) {
            // The body is run through %s template substitution even with no
            // arguments, so a literal "%%" would silently collapse to "%".
            // Escaping every '%' round-trips back to the original text exactly.
            message(if (message.contains('%')) message.replace("%", "%%") else message)
            if (tag.isNotBlank()) {
                attributes { this["logger"] = tag }
            }
        }
    }

    /** Only called for priorities [CrashReportPolicy.shouldSendAsLog] accepts. */
    private fun Int.toSentryLogLevel(): SentryLogLevel = when {
        this >= CrashReportPolicy.PRIORITY_ERROR -> SentryLogLevel.ERROR
        this >= CrashReportPolicy.PRIORITY_WARN -> SentryLogLevel.WARN
        else -> SentryLogLevel.INFO
    }

    private fun Int.toSentryLevel(): SentryLevel = when {
        this >= CrashReportPolicy.PRIORITY_ERROR -> SentryLevel.ERROR
        this >= CrashReportPolicy.PRIORITY_WARN -> SentryLevel.WARNING
        this >= CrashReportPolicy.PRIORITY_INFO -> SentryLevel.INFO
        else -> SentryLevel.DEBUG
    }
}
