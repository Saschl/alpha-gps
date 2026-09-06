package com.sasch.cameragps.sharednew.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewPromptControllerTest {
    private val day = 86_400L
    private var now = 1_000_000L
    private var nativeRequests = 0
    private var nativeWindowAvailable = true
    private val store = object : ReviewPromptStore {
        var state: ReviewPromptState? = null
        override fun read() = state
        override fun write(state: ReviewPromptState) { this.state = state }
    }

    private fun newController() = ReviewPromptController(store, { now }) {
        nativeRequests++
        nativeWindowAvailable
    }

    private val controller = newController()
    private fun request() = controller.requestIfDue(isForeground = true, hasSavedCamera = true, canPresent = true)

    @Test
    fun backgroundLaunchAndMissingCameraDoNotStartTheGracePeriod() {
        assertFalse(controller.requestIfDue(isForeground = false, hasSavedCamera = true, canPresent = true))
        assertFalse(controller.requestIfDue(isForeground = true, hasSavedCamera = false, canPresent = true))
        assertNull(store.read())
        assertEquals(0, nativeRequests)
    }

    @Test
    fun firstRequestWaitsOneFullDayAfterSetup() {
        assertFalse(request())
        now += day - 1
        assertFalse(request())
        now++
        assertTrue(request())
        assertEquals(1, nativeRequests)
        assertEquals(1, store.read()?.requestCount)
        assertEquals(now, store.read()?.lastRequestedAtSeconds)
    }

    @Test
    fun busyUiInitializesTheGracePeriodButDefersDueRequests() {
        assertFalse(controller.requestIfDue(isForeground = true, hasSavedCamera = true, canPresent = false))
        assertEquals(now, store.read()?.firstEligibleAtSeconds)
        now += day
        assertFalse(controller.requestIfDue(isForeground = true, hasSavedCamera = true, canPresent = false))
        assertEquals(0, nativeRequests)
        assertEquals(0, store.read()?.requestCount)
        assertTrue(request())
    }

    @Test
    fun dueRequestStillNeedsAForegroundCamera() {
        request()
        now += day
        assertFalse(controller.requestIfDue(isForeground = false, hasSavedCamera = true, canPresent = true))
        assertFalse(controller.requestIfDue(isForeground = true, hasSavedCamera = false, canPresent = true))
        assertEquals(0, nativeRequests)
        assertTrue(request())
    }

    @Test
    fun repeatedRequestsAndRelaunchesRespectTheThirtyDayCooldown() {
        request()
        now += day
        assertTrue(request())
        val relaunched = newController()
        assertFalse(relaunched.requestIfDue(isForeground = true, hasSavedCamera = true, canPresent = true))
        now += 30 * day - 1
        assertFalse(request())
        now++
        assertTrue(request())
        assertEquals(2, nativeRequests)
    }

    @Test
    fun thirdRequestIsTheLastEvenAfterARelaunch() {
        request()
        now += day
        repeat(3) {
            assertTrue(request())
            now += 30 * day
        }
        now += 365 * day
        assertFalse(newController().requestIfDue(isForeground = true, hasSavedCamera = true, canPresent = true))
        assertEquals(3, nativeRequests)
        assertEquals(3, store.read()?.requestCount)
    }

    @Test
    fun missingNativeWindowDoesNotConsumeAnAttempt() {
        request()
        now += day
        nativeWindowAvailable = false
        val before = store.read()
        assertFalse(request())
        assertEquals(before, store.read())
        nativeWindowAvailable = true
        assertTrue(request())
        assertEquals(1, store.read()?.requestCount)
    }

    @Test
    fun clockMovingBackwardsCannotBypassEitherWaitingPeriod() {
        request()
        val setupTime = now
        now -= day
        assertFalse(request())
        now = setupTime + day
        assertTrue(request())
        now -= day
        assertFalse(request())
        assertEquals(1, nativeRequests)
    }
}
