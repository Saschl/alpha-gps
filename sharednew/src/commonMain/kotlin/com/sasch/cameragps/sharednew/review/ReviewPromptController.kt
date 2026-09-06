package com.sasch.cameragps.sharednew.review

internal data class ReviewPromptState(
    val firstEligibleAtSeconds: Long,
    val lastRequestedAtSeconds: Long? = null,
    val requestCount: Int = 0,
)

internal interface ReviewPromptStore {
    fun read(): ReviewPromptState?
    fun write(state: ReviewPromptState)
}

/**
 * Matches Android's schedule: one day after setup, then 30 days between requests,
 * with at most three attempts per installation. Counts requests, never reviews:
 * the stores do not tell us whether a prompt was displayed or a rating submitted.
 * All calls are confined to the UI thread.
 */
internal class ReviewPromptController(
    private val store: ReviewPromptStore,
    private val nowSeconds: () -> Long,
    private val requestReview: () -> Boolean,
) {
    fun requestIfDue(isForeground: Boolean, hasSavedCamera: Boolean, canPresent: Boolean): Boolean {
        if (!isForeground || !hasSavedCamera) return false
        val now = nowSeconds()
        val state = store.read() ?: ReviewPromptState(firstEligibleAtSeconds = now).also(store::write)
        if (!canPresent || state.requestCount >= 3) return false
        val reference = state.lastRequestedAtSeconds ?: state.firstEligibleAtSeconds
        val waitSeconds = if (state.lastRequestedAtSeconds == null) DAY_SECONDS else 30 * DAY_SECONDS
        if (now < reference || now - reference < waitSeconds) return false
        // A missing/obscured native window is a deferral, not a used attempt.
        if (!requestReview()) return false
        store.write(state.copy(lastRequestedAtSeconds = now, requestCount = state.requestCount + 1))
        return true
    }

    private companion object {
        const val DAY_SECONDS = 24 * 60 * 60L
    }
}
