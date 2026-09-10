package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** One native picker attempt; callbacks and dismissal can arrive in either order. */
internal class AccessoryPickerCompletion<T>(
    private val completed: T,
    private val waitForDismissalOnSuccess: Boolean = false,
) {
    private val result = CompletableDeferred<T>()

    /**
     * AccessorySetupKit presented a picker for this attempt.
     *
     * Only `pickerDidDismiss` ends a visible picker. Finishing the attempt while
     * one is on screen leaves the session's picker active, and the next
     * `showPicker` — typically "add a camera" — then fails with
     * `ASErrorCodePickerAlreadyActive`. Migration cannot be assumed invisible:
     * that was observed for a single accessory, not promised by Apple.
     */
    private var presented = false

    /**
     * Ticks on every sign of life from the native flow, so [await] can tell a
     * slow migration from one that has silently stopped.
     */
    private val progress = MutableStateFlow(0)

    private val endsOnDismissalOnly: Boolean get() = waitForDismissalOnSuccess || presented

    /** The system put a picker on screen; from here only dismissal ends the attempt. */
    fun onPresented() {
        presented = true
        progress.value++
    }

    fun onCompletion(value: T) {
        // A successful showPicker callback must not tear down a visible
        // picker's event handler. Only dismissal ends that lifetime.
        // Migration without a picker keeps callback completion.
        if (endsOnDismissalOnly && value == completed) return
        result.complete(value)
    }

    /**
     * One accessory of a multi-accessory migration finished while others are
     * still pending. Not terminal: `migrationComplete` is documented as "the
     * migration of an accessory completed", so the native flow is still running
     * and the attempt must not hand the central back underneath it.
     */
    fun onMigrationProgress() {
        progress.value++
    }

    /** Every accessory of the attempt is migrated. */
    fun onMigrationComplete() {
        progress.value++
        // A migration that showed no picker gets no dismissal at all, so this is
        // the only terminal signal that does not depend on foregrounding the app.
        if (presented) return
        result.complete(completed)
    }

    fun onDismissed() {
        result.complete(completed)
    }

    suspend fun await(): T = result.await()

    /**
     * Await the attempt, treating [settleTimeout] of complete silence as the end
     * of it while no picker is on screen.
     *
     * A migration can stop short — a skipped or failed accessory reports
     * nothing, and neither dismissal nor the completion closure is guaranteed —
     * and without this fallback the attempt, the central it holds and the busy
     * dialog over it would all wait for a signal that never comes. A presented
     * picker is exempt: it waits on the person, not on a timer.
     */
    suspend fun await(settleTimeout: Duration): T = coroutineScope {
        val settle = launch {
            var seen = progress.value
            while (true) {
                val next = withTimeoutOrNull(settleTimeout) { progress.first { it > seen } }
                if (next == null) {
                    if (presented) return@launch
                    break
                }
                seen = next
            }
            result.complete(completed)
        }
        try {
            result.await()
        } finally {
            settle.cancel()
        }
    }
}
