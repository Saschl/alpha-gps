package com.sasch.cameragps.sharednew.ui.dialog

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DialogHostTest {
    @Test
    fun compositionQueuesAdvancesPausesAndCancelsRequests() = runTest {
        val queue = DialogQueue<String>()
        val visible = mutableSetOf<String>()
        queue.enqueue("first")
        queue.enqueue("second")
        val dismissCallbacks = mutableMapOf<String, () -> Unit>()
        var canShow by mutableStateOf(true)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val composition = Composition(NoOpApplier(), recomposer)
        val job = launch(clock) { recomposer.runRecomposeAndApplyChanges() }

        fun settle() {
            repeat(5) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(it.toLong())
                runCurrent()
            }
        }

        try {
            composition.setContent {
                DialogHost(queue, canShow) { dialog, dismiss ->
                    DisposableEffect(Unit) {
                        assertTrue(visible.isEmpty(), "Only one dialog may be composed at a time")
                        visible.add(dialog)
                        dismissCallbacks[dialog] = dismiss
                        onDispose { visible.remove(dialog) }
                    }
                }
            }
            settle()
            assertEquals(setOf("first"), visible)

            // Backgrounding hides the dialog without dropping either request.
            canShow = false
            settle()
            assertTrue(visible.isEmpty())
            assertEquals("first", queue.activeDialog)
            canShow = true
            settle()
            assertEquals(setOf("first"), visible)

            dismissCallbacks.getValue("first")()
            settle()
            assertEquals(setOf("second"), visible)

            // A fresh request joins the tail, and can be withdrawn before showing.
            queue.enqueue("first")
            settle()
            assertEquals(setOf("second"), visible)
            queue.remove("first")
            // A stale callback must not close the current dialog.
            dismissCallbacks.getValue("first")()
            settle()
            assertEquals(setOf("second"), visible)
            dismissCallbacks.getValue("second")()
            settle()
            assertTrue(visible.isEmpty())
            assertTrue(queue.isEmpty)

            queue.enqueue("first")
            queue.enqueue("second")
            settle()
            assertEquals(setOf("first"), visible)
        } finally {
            composition.dispose()
            recomposer.close()
            job.cancelAndJoin()
        }
        // The host owns presentation; the queue owns requests, even after disposal.
        assertEquals("first", queue.activeDialog)
        assertTrue(visible.isEmpty())
    }

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
