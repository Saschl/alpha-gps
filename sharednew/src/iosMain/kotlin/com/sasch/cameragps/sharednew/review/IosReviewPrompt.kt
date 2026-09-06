package com.sasch.cameragps.sharednew.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.coroutines.delay
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIViewController

internal class IosReviewPromptStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : ReviewPromptStore {
    override fun read(): ReviewPromptState? {
        if (defaults.objectForKey(FIRST_ELIGIBLE) == null) return null
        return ReviewPromptState(
            firstEligibleAtSeconds = defaults.doubleForKey(FIRST_ELIGIBLE).toLong(),
            lastRequestedAtSeconds = if (defaults.objectForKey(LAST_REQUESTED) == null) null
                else defaults.doubleForKey(LAST_REQUESTED).toLong(),
            requestCount = defaults.integerForKey(REQUEST_COUNT).toInt(),
        )
    }

    override fun write(state: ReviewPromptState) {
        defaults.setDouble(state.firstEligibleAtSeconds.toDouble(), forKey = FIRST_ELIGIBLE)
        state.lastRequestedAtSeconds?.let { defaults.setDouble(it.toDouble(), forKey = LAST_REQUESTED) }
            ?: defaults.removeObjectForKey(LAST_REQUESTED)
        defaults.setInteger(state.requestCount.toLong(), forKey = REQUEST_COUNT)
    }

    private companion object {
        const val FIRST_ELIGIBLE = "ios.review.firstEligibleAt"
        const val LAST_REQUESTED = "ios.review.lastRequestedAt"
        const val REQUEST_COUNT = "ios.review.requestCount"
    }
}

@Composable
internal fun IosReviewPromptEffect(
    isForeground: Boolean,
    hasSavedCamera: Boolean,
    canPresent: Boolean,
    hasCompetingPrompt: Boolean,
    requestReview: (UIViewController) -> Boolean,
) {
    val viewController = LocalUIViewController.current
    val currentRequestReview by rememberUpdatedState(requestReview)
    val controller = remember(viewController) {
        ReviewPromptController(
            store = IosReviewPromptStore(),
            nowSeconds = { NSDate().timeIntervalSince1970().toLong() },
            requestReview = { currentRequestReview(viewController) },
        )
    }
    var hadCompetingPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(isForeground, hasSavedCamera, canPresent, hasCompetingPrompt) {
        if (!isForeground) {
            hadCompetingPrompt = false
            return@LaunchedEffect
        }
        if (hasCompetingPrompt) hadCompetingPrompt = true
        // Start the setup grace period even when another dialog takes priority.
        controller.requestIfDue(isForeground = true, hasSavedCamera = hasSavedCamera, canPresent = false)
        if (!canPresent || hadCompetingPrompt || !hasSavedCamera) return@LaunchedEffect
        // Let the device list settle. Navigation, backgrounding, and new dialogs
        // cancel this effect; don't stack a review immediately after another ask.
        delay(2_000)
        controller.requestIfDue(isForeground = true, hasSavedCamera = true, canPresent = true)
    }
}
