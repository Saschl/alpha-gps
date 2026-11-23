package com.sasch.cameragps.sharednew.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BulbTimerTest {
    @Test
    fun countdownUsesDeadlineAndStopsOnce() = runTest {
        val states = mutableListOf<BulbTimerState>()
        val exposure = BulbExposure(5, states::add, testScheduler.timeSource)
        val job = launch { exposure.hold() }
        runCurrent()
        assertEquals(BulbTimerState(BulbPhase.Exposing, 5), states.last())
        advanceTimeBy(2200)
        runCurrent()
        assertEquals(3, states.last().remainingSeconds)
        exposure.stop()
        exposure.stop()
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, states.count { it.phase == BulbPhase.Stopping })
        advanceTimeBy(5000)
        assertEquals(BulbPhase.Stopping, states.last().phase)
    }

    @Test
    fun expiryEndsHoldWithoutUserInput() = runTest {
        val exposure = BulbExposure(5, {}, testScheduler.timeSource)
        val job = launch { exposure.hold() }
        advanceTimeBy(4999)
        assertFalse(job.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(job.isCompleted)
    }
}
