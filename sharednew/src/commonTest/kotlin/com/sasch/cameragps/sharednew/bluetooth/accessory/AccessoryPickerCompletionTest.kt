package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
