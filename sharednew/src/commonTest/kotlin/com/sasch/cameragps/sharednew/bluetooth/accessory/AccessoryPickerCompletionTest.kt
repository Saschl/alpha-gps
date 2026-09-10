package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AccessoryPickerCompletionTest {
    @Test
    fun successfulDiscoveryCallbackKeepsHandlerAndExclusiveOwnershipUntilDismissal() = runTest {
        val runner = AccessoryPickerRunner(backgroundScope)
        val attempt = AccessoryPickerCompletion(true, waitForDismissalOnSuccess = true)
        var handlerActive = false
        val ui = launch {
            runner.run {
                handlerActive = true
                try {
                    attempt.await()
                } finally {
                    handlerActive = false
                }
            }
        }
        runCurrent()
        attempt.onCompletion(true)
        runCurrent()
        assertTrue(handlerActive)
        assertFalse(ui.isCompleted)
        assertFalse(runner.run { error("Discovery picker is still visible") })

        attempt.onDismissed()
        runCurrent()
        assertFalse(handlerActive)
        assertTrue(ui.isCompleted)
        assertTrue(runner.run { true })
    }

    @Test
    fun failedDiscoveryCallbackEndsAttemptWithoutRequiringDismissal() = runTest {
        val attempt = AccessoryPickerCompletion(true, waitForDismissalOnSuccess = true)
        val result = async { attempt.await() }
        attempt.onCompletion(false)
        assertFalse(result.await())
        attempt.onDismissed()
        assertFalse(attempt.await())
    }

    @Test
    fun dismissalBeforeCallbackCompletesDiscoveryAndIgnoresLateErrors() = runTest {
        val attempt = AccessoryPickerCompletion(true, waitForDismissalOnSuccess = true)
        attempt.onDismissed()
        assertTrue(attempt.await())
        attempt.onCompletion(true)
        attempt.onCompletion(false)
        assertTrue(attempt.await())
    }

    @Test
    fun migrationSuccessCallbackStillCompletesWithoutVisiblePicker() = runTest {
        val attempt = AccessoryPickerCompletion(true)
        attempt.onCompletion(true)
        assertTrue(attempt.await())
    }

    @Test
    fun migrationCompleteRestartsCentralWithoutDismissalOrCompletionClosure() = runTest {
        val runner = AccessoryPickerRunner(backgroundScope)
        val attempt = AccessoryPickerCompletion(true)
        var centralRunning = false
        val ui = launch {
            runner.run {
                try {
                    attempt.await()
                } finally {
                    centralRunning = true
                }
            }
        }
        runCurrent()
        assertFalse(centralRunning)
        attempt.onMigrationComplete()
        runCurrent()
        assertTrue(centralRunning)
        assertTrue(ui.isCompleted)

        // Late native signals cannot change the completed attempt's result.
        attempt.onDismissed()
        attempt.onCompletion(false)
        assertTrue(attempt.await())
    }

    @Test
    fun migrationOfSeveralAccessoriesRunsOnAfterTheFirstOneCompletes() = runTest {
        val attempt = AccessoryPickerCompletion(true)
        val result = async { attempt.await(SETTLE) }
        runCurrent()

        // One accessory of two: the native flow is still working through the rest,
        // so handing the central back here would leave its picker active and fail
        // the next request with ASErrorCodePickerAlreadyActive.
        attempt.onMigrationProgress()
        advanceTimeBy(SETTLE_MS - 1)
        runCurrent()
        assertFalse(result.isCompleted)

        attempt.onMigrationComplete()
        runCurrent()
        assertTrue(result.await())
    }

    @Test
    fun migrationProgressKeepsExtendingTheSettleTimeout() = runTest {
        val attempt = AccessoryPickerCompletion(true)
        val result = async { attempt.await(SETTLE) }
        runCurrent()

        repeat(3) {
            attempt.onMigrationProgress()
            advanceTimeBy(SETTLE_MS - 1)
            runCurrent()
            assertFalse(result.isCompleted)
        }

        // Silence, not progress, is what ends a migration nothing else terminates.
        advanceTimeBy(2)
        runCurrent()
        assertTrue(result.await())
    }

    @Test
    fun migrationThatReportsNothingAtAllStillReleasesTheCentral() = runTest {
        val attempt = AccessoryPickerCompletion(true)
        val result = async { attempt.await(SETTLE) }
        advanceTimeBy(SETTLE_MS - 1)
        runCurrent()
        assertFalse(result.isCompleted)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(result.await())
    }

    @Test
    fun presentedMigrationPickerOnlyEndsWhenTheSystemDismissesIt() = runTest {
        val attempt = AccessoryPickerCompletion(true)
        val result = async { attempt.await(SETTLE) }
        runCurrent()
        attempt.onPresented()

        // A visible picker waits on the person: neither the per-accessory events,
        // nor a success callback, nor the settle timeout may end the attempt.
        attempt.onMigrationProgress()
        attempt.onMigrationComplete()
        attempt.onCompletion(true)
        advanceTimeBy(10 * SETTLE_MS)
        runCurrent()
        assertFalse(result.isCompleted)

        attempt.onDismissed()
        runCurrent()
        assertTrue(result.await())
    }

    @Test
    fun presentedPickerStillEndsOnAFailedCallback() = runTest {
        val attempt = AccessoryPickerCompletion(true)
        val result = async { attempt.await(SETTLE) }
        runCurrent()
        attempt.onPresented()
        attempt.onCompletion(false)
        runCurrent()
        assertFalse(result.await())
    }

    private companion object {
        private const val SETTLE_MS = 10_000L
        private val SETTLE = 10.seconds
    }
}
