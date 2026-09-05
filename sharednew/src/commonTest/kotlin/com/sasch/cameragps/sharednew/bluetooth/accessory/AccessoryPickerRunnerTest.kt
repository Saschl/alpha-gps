package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
            runner.run {
                try {
                    dismissed.await()
                    true
                } finally {
                    recovered = true
                    assertFalse(runner.run { error("Request during recovery") })
                }
            }
        }
        runCurrent()
        ui.cancel()
        runCurrent()
        assertFalse(recovered)
        assertFalse(runner.run { error("Concurrent picker") })
        dismissed.complete(Unit)
        runCurrent()
        assertTrue(recovered)
        assertTrue(runner.run { true })
    }

    @Test
    fun failedPickerAllowsRetry() = runTest {
        val runner = AccessoryPickerRunner(backgroundScope)
        assertFalse(runner.run { false })
        assertTrue(runner.run { true })
    }

    @Test
    fun exceptionReleasesOwnership() = runTest {
        // Match the controller's supervisor scope: an operation failure must
        // not cancel the owner that needs to accept the next picker request.
        val owner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        try {
            val runner = AccessoryPickerRunner(owner)
            assertFailsWith<IllegalStateException> {
                runner.run { error("Native picker failure") }
            }
            assertTrue(runner.run { true })
        } finally {
            owner.cancel()
        }
    }
}
