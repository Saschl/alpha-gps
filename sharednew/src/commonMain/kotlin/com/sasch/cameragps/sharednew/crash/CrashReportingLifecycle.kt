package com.sasch.cameragps.sharednew.crash

/** Applies consent changes on the main thread without installing the SDK twice. */
class CrashReportingLifecycle(
    private val start: () -> Unit,
    private val stop: () -> Unit,
) {
    var isStarted: Boolean = false
        private set

    fun update(enabled: Boolean, consentDialogDismissed: Boolean) {
        val shouldStart = CrashReportPolicy.shouldInitialize(enabled, consentDialogDismissed)
        if (shouldStart == isStarted) return

        if (shouldStart) {
            start()
            isStarted = true
        } else {
            isStarted = false
            stop()
        }
    }
}
