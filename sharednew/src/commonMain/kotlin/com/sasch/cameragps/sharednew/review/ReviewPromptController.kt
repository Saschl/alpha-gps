package com.sasch.cameragps.sharednew.review

internal data class ReviewPromptState(
    val firstEligibleAtSeconds: Long,
    val lastRequestedAtSeconds: Long? = null,
)

internal interface ReviewPromptStore {
    fun read(): ReviewPromptState?
    fun write(state: ReviewPromptState)
}

/**
 * iOS schedule: one day after setup, then 30 days between requests, without a
 * lifetime cap. Records request timing, never confirmed displays or reviews:
 * StoreKit does not tell us whether a prompt was displayed or a rating submitted.
 * All calls are confined to the UI thread.
 */
internal class ReviewPromptController(
    private val store: ReviewPromptStore,
    private val nowSeconds: () -> Long,
    private val testMode: Boolean = false,
    private val requestReview: () -> Boolean,
) {
    private var testRequestIssued = false

    fun requestIfDue(isForeground: Boolean, hasSavedCamera: Boolean, canPresent: Boolean): Boolean {
        // The iOS Debug host opts in explicitly. Exercise native presentation
        // once per controller without reading or changing real request history.
        if (testMode) {
            if (!isForeground || !canPresent || testRequestIssued) return false
            if (!requestReview()) return false
            testRequestIssued = true
            return true
        }
        if (!isForeground || !hasSavedCamera) return false
        val now = nowSeconds()
        val state = store.read() ?: ReviewPromptState(firstEligibleAtSeconds = now).also(store::write)
        if (!canPresent) return false
        val reference = state.lastRequestedAtSeconds ?: state.firstEligibleAtSeconds
        val waitSeconds = if (state.lastRequestedAtSeconds == null) DAY_SECONDS else 30 * DAY_SECONDS
        if (now < reference || now - reference < waitSeconds) return false
        // A missing/obscured native window is a deferral, not a used attempt.
        if (!requestReview()) return false
        store.write(state.copy(lastRequestedAtSeconds = now))
        return true
    }

    private companion object {
        const val DAY_SECONDS = 24 * 60 * 60L
    }
}
