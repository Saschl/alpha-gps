package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccessoryPickerRunnerTest {
    @Test
    fun cancellingUiDoesNotReleasePickerAndOverlappingRequestsAreRejected() = runTest {
        val runner = AccessoryPickerRunner(backgroundScope)
        val dismissed = CompletableDeferred<Unit>()
        var recovered = false
        val ui = launch {
            runner.run(true, recover = {
                assertFalse(runner.migrationInProgress.value)
                recovered = true
            }) {
                dismissed.await()
                true
            }
        }
        runCurrent()
        ui.cancel()
        runCurrent()
        assertTrue(runner.migrationInProgress.value)
        assertFalse(recovered)
        assertFalse(runner.run(true) { error("Duplicate migration") })
        assertFalse(runner.run(false) { error("Concurrent discovery") })
        dismissed.complete(Unit)
        runCurrent()
        assertTrue(recovered)
        assertFalse(runner.migrationInProgress.value)
        assertTrue(runner.run(false) { true })
    }

    @Test
    fun failedPickerRestoresServiceAndAllowsRetry() = runTest {
        val runner = AccessoryPickerRunner(backgroundScope)
        var recovered = false
        assertFalse(runner.run(true, recover = { recovered = true }) { false })
        assertTrue(recovered)
        assertFalse(runner.migrationInProgress.value)
        assertTrue(runner.run(true) { true })
    }
}
