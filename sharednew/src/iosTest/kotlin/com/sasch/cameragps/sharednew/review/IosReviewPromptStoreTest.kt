package com.sasch.cameragps.sharednew.review

import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

            val requested = setup.copy(lastRequestedAtSeconds = 1_086_400, requestCount = 1)
            store.write(requested)
            assertEquals(requested, IosReviewPromptStore(defaults).read())
        } finally {
            defaults.removePersistentDomainForName(suite)
        }
    }
}
