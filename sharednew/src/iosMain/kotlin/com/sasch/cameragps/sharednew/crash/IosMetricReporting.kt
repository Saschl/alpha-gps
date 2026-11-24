package com.sasch.cameragps.sharednew.crash

import com.sasch.cameragps.sharednew.IosAppPreferences

/** Swift owns the iOS 27 API references so the app can still launch on older iOS versions. */
interface IosMetricReportCollector {
    fun setEnabled(enabled: Boolean)
}

/** Main-thread bridge between the Swift collector and the consent-controlled Sentry SDK. */
object IosMetricReporting {
    private var collector: IosMetricReportCollector? = null

    fun install(collector: IosMetricReportCollector) {
        this.collector?.setEnabled(false)
        this.collector = collector
        updateConsent()
    }

    internal fun updateConsent() {
        collector?.setEnabled(canReport())
    }

    private fun canReport(): Boolean = IosCrashReporting.isStarted &&
            CrashReportPolicy.shouldInitialize(
                enabled = IosAppPreferences.isSentryEnabled(),
                consentDialogDismissed = IosAppPreferences.isSentryConsentDialogDismissed(),
            )
}
