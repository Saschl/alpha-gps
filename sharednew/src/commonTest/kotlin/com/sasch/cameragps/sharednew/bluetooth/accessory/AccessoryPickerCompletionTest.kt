package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccessoryPickerCompletionTest {
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
