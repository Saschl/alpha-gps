package com.saschl.cameragps.utils

import android.content.Context
import com.sasch.cameragps.sharednew.crash.CrashReportPolicy
import io.sentry.SentryLevel
import io.sentry.SentryLogLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.timber.SentryTimberIntegration

/**
 * gplay crash reporting: Sentry. Only ever initialized after the user consented
 * (see SentryConsentDialog / CameraGpsApplication). The foss flavor ships a
 * no-op counterpart of this object.
 */
object CrashReporting {

    /** Gates the consent dialog and the Sentry settings entry. */
    const val AVAILABLE = true

    fun init(context: Context) {
        SentryAndroid.init(context) { options ->
            options.isSendDefaultPii = false

            options.logs.isEnabled = true

            // These thresholds are the ones CrashReportPolicy.route encodes for
            // both platforms — change them together.
            options.addIntegration(
                SentryTimberIntegration(
                    minEventLevel = SentryLevel.ERROR,
                    minBreadcrumbLevel = SentryLevel.INFO,
                    minLogsLevel = SentryLogLevel.INFO
                )
            )
            options.logs.beforeSend = SentryOptions.Logs.BeforeSendLogCallback { event ->
                // Scrubbing lives in the shared module so iOS redacts identically.
                event.body = CrashReportPolicy.redact(event.body)
                event
            }
        }
    }
}
