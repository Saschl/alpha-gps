package com.sasch.cameragps.sharednew.review

import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosReviewPromptStoreTest {
    @Test
    fun setupAndRequestHistorySurviveRecreatingTheStore() {
        val suite = "review-test-${NSUUID().UUIDString}"
        val defaults = NSUserDefaults(suiteName = suite)
        try {
            val store = IosReviewPromptStore(defaults)
            assertNull(store.read())
            val setup = ReviewPromptState(firstEligibleAtSeconds = 1_000_000)
            store.write(setup)
            assertEquals(setup, IosReviewPromptStore(defaults).read())

            val requested = setup.copy(lastRequestedAtSeconds = 1_086_400)
            store.write(requested)
            assertEquals(requested, IosReviewPromptStore(defaults).read())
        } finally {
            defaults.removePersistentDomainForName(suite)
        }
    }

    @Test
    fun legacyLifetimeCountIsIgnoredWhileExistingCooldownIsPreserved() {
        val suite = "review-test-${NSUUID().UUIDString}"
        val defaults = NSUserDefaults(suiteName = suite)
        try {
            defaults.setDouble(1_000_000.0, forKey = "ios.review.firstEligibleAt")
            defaults.setDouble(1_086_400.0, forKey = "ios.review.lastRequestedAt")
            defaults.setInteger(3, forKey = "ios.review.requestCount")
            var now = 1_086_400L + 30 * 86_400 - 1
            var nativeRequests = 0
            fun controller() = ReviewPromptController(IosReviewPromptStore(defaults), { now }) {
                nativeRequests++
                true
            }
            assertFalse(controller().requestIfDue(true, true, true))
            assertEquals(0, nativeRequests)
            now++
            assertTrue(controller().requestIfDue(true, true, true))
            assertEquals(1, nativeRequests)
            assertEquals(now, IosReviewPromptStore(defaults).read()?.lastRequestedAtSeconds)
            assertFalse(controller().requestIfDue(true, true, true))
            assertEquals(1, nativeRequests)
        } finally {
            defaults.removePersistentDomainForName(suite)
        }
    }
}
