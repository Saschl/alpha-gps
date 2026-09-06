package com.sasch.cameragps.sharednew.crash

import com.diamondedge.logging.KmLogging
import com.sasch.cameragps.sharednew.IosAppPreferences
import com.sasch.cameragps.sharednew.crash.IosCrashReporting.AVAILABLE
import com.sasch.cameragps.sharednew.crash.IosCrashReporting.start
import com.sasch.cameragps.sharednew.crash.IosCrashReporting.started
import io.sentry.kotlin.multiplatform.Sentry
import platform.Foundation.NSBundle

/**
 * iOS counterpart of the Android `com.saschl.cameragps.utils.CrashReporting`
 * object: the single place that starts Sentry, and only ever after the user
 * consented (see [com.sasch.cameragps.sharednew.ui.settings.SharedSentryConsentDialog]).
 *
 * Unlike Android there is no foss counterpart — iOS ships one build, so
 * [AVAILABLE] is a constant. Everything Sentry-typed lives in `iosMain`, which
 * is what keeps the Android foss flavor free of the SDK.
 *
 * Runs on the main thread only: `ensureInitialized` and the settings UI are the
 * two callers, both `Dispatchers.Main.immediate`.
 */
internal object IosCrashReporting {

    /** Gates the consent dialog and the settings entry, mirroring Android. */
    const val AVAILABLE: Boolean = true

    private const val DSN =
        "https://2b23f28f72b9b9d4f4e3ec950e9cb461@o4510501457494016.ingest.de.sentry.io/4512036235182160"

    private var started = false

    /** Forwards log lines to Sentry; only attached while [started]. */
    private val logger by lazy { SentryCrashLogger() }

    /** True once [start] actually brought the SDK up. */
    val isStarted: Boolean get() = started

    /**
     * The KmLogging logger to install alongside the database logger, or null
     * while crash reporting is off.
     *
     * `KmLogging.setLoggers` replaces the whole logger list, so every caller
     * that reconfigures logging has to ask for this again — see
     * [com.sasch.cameragps.sharednew.logging.IosLogging.install].
     */
    fun activeLogger(): SentryCrashLogger? = if (started) logger else null

    /** Starts Sentry if the user has opted in. Called once per launch. */
    fun startIfConsented() {
        if (
            CrashReportPolicy.shouldInitialize(
                enabled = IosAppPreferences.isSentryEnabled(),
                consentDialogDismissed = IosAppPreferences.isSentryConsentDialogDismissed(),
            )
        ) {
            start()
        }
    }

    /**
     * Brings the SDK up and starts forwarding log lines. Idempotent; also
     * called straight from the consent dialog so accepting takes effect without
     * a restart.
     */
    fun start() {
        if (started || DSN.isBlank()) return

        Sentry.init { options ->
            options.dsn = DSN
            // Never attach the device name, IP or other identifying data: the
            // Android build does the same and the log stream is scrubbed below.
            options.sendDefaultPii = false
            options.release = bundleRelease()
            options.logs.enabled = true
            options.logs.beforeSend = { log ->
                log.body = CrashReportPolicy.redact(log.body)
                log
            }
            options.beforeSend = { event ->
                event.message?.let { message ->
                    message.message = message.message?.let(CrashReportPolicy::redact)
                    message.formatted = message.formatted?.let(CrashReportPolicy::redact)
                }
                event
            }
            // The whole iOS app is Kotlin/Native running inside Compose
            // Multiplatform. With Cocoa's C++ monitor enabled, an unhandled
            // Kotlin exception thrown from a Compose callback is reported as
            // Kotlin/Native's internal ExceptionObjHolderImpl instead of
            // reaching the KMP hook that knows the real Kotlin stack trace.
            options.enableUnhandledCppExceptionMonitoring = false
        }

        started = true
        KmLogging.addLogger(logger)
    }

    private fun bundleRelease(): String {
        val bundle = NSBundle.mainBundle
        val identifier = bundle.bundleIdentifier ?: "com.saschl.cameragps"
        val shortVersion =
            bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        val build = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String
        // Same shape Sentry Cocoa derives itself, so releases line up with the
        // dSYMs uploaded for a build.
        return "$identifier@${shortVersion.orEmpty()}+${build.orEmpty()}"
    }
}
