package com.sasch.cameragps.sharednew.crash

import com.sasch.cameragps.sharednew.crash.CrashReportPolicy.route


/**
 * Where a single log line ends up once crash reporting is switched on.
 *
 * @see CrashReportPolicy.route
 */
enum class CrashReportRoute {
    /** Dropped — too noisy to leave the device. */
    Ignore,

    /** Kept as context for a later event, but not reported on its own. */
    Breadcrumb,

    /** Reported to Sentry as its own issue. */
    Event,
}

/**
 * The crash-reporting rules both platforms share.
 *
 * Android runs Sentry through `SentryTimberIntegration` and iOS through a
 * `KmLogging` logger, so the two can only stay in sync if the decisions live in
 * one place. Everything here is pure — no Sentry types, so `commonMain` (and
 * with it the Android **foss** flavor) stays free of the SDK.
 */
object CrashReportPolicy {

    /**
     * Log priorities as used by `android.util.Log` and by
     * `com.sasch.cameragps.sharednew.database.logging.DatabaseLogger`.
     */
    const val PRIORITY_VERBOSE: Int = 2
    const val PRIORITY_DEBUG: Int = 3
    const val PRIORITY_INFO: Int = 4
    const val PRIORITY_WARN: Int = 5
    const val PRIORITY_ERROR: Int = 6

    /** What a MAC address is replaced with in anything leaving the device. */
    const val MAC_PLACEHOLDER: String = "XX:XX:XX:XX:XX:XX"

    private val macRegex = Regex("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})")

    /**
     * Mirrors the Android integration's thresholds — `minBreadcrumbLevel = INFO`,
     * `minEventLevel = ERROR` — so an iOS log line is treated like the Android
     * one with the same priority.
     */
    fun route(priority: Int): CrashReportRoute = when {
        priority >= PRIORITY_ERROR -> CrashReportRoute.Event
        priority >= PRIORITY_INFO -> CrashReportRoute.Breadcrumb
        else -> CrashReportRoute.Ignore
    }

    /**
     * Whether a log line is ALSO mirrored into Sentry's structured **Logs**,
     * mirroring Android's `minLogsLevel = INFO`.
     *
     * Independent of [route], not an alternative to it: on both platforms an
     * ERROR is an issue *and* a log line, an INFO is a breadcrumb *and* a log
     * line. Breadcrumbs only ever surface attached to an event; the Logs view
     * is what you read when nothing crashed.
     */
    fun shouldSendAsLog(priority: Int): Boolean = priority >= PRIORITY_INFO

    /**
     * Strips camera/phone MAC addresses out of [message]. BLE addresses show up
     * all over the log stream and identify a specific device, so they never
     * leave the phone.
     */
    fun redact(message: String): String = message.replace(macRegex, MAC_PLACEHOLDER)

    /**
     * Whether the SDK may be started. Consent is only real once the user has
     * actually answered the dialog: an untouched `enabled` default must never
     * start reporting on its own.
     */
    fun shouldInitialize(enabled: Boolean, consentDialogDismissed: Boolean): Boolean =
        enabled && consentDialogDismissed

    /**
     * Whether the consent dialog is still owed to the user. [available] is false
     * on builds without a crash reporter at all (the Android foss flavor).
     */
    fun shouldShowConsentDialog(available: Boolean, consentDialogDismissed: Boolean): Boolean =
        available && !consentDialogDismissed
}
