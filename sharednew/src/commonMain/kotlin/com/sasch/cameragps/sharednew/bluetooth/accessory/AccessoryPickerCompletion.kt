package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
     * Await the attempt.
     *
     * [settleTimeout] of silence ends it while nothing is on screen: a migration
     * whose accessory was skipped reports nothing at all. A presented picker
     * ignores that — it waits on the person — but still ends at [visibleTimeout],
     * because the attempt holds the guard that blocks central creation and a lost
     * `pickerDidDismiss` would mean no cameras until the app restarts.
     */
    suspend fun await(
        settleTimeout: Duration,
        visibleTimeout: Duration = VISIBLE_PICKER_TIMEOUT,
    ): T = coroutineScope {
        val watchdog = launch {
            var seen = progress.value
            while (!presented) {
                val next = withTimeoutOrNull(settleTimeout) { progress.first { it > seen } }
                if (next == null) {
                    // Nothing on screen and nothing happening: the attempt is over.
                    result.complete(completed)
                    return@launch
                }
                seen = next
            }
            delay(visibleTimeout)
            result.complete(completed)
        }
        try {
            result.await()
        } finally {
            watchdog.cancel()
        }
    }

    internal companion object {
        /** Long enough that someone walking over to switch the camera into
         *  pairing mode never hits it. */
        internal val VISIBLE_PICKER_TIMEOUT: Duration = 5.minutes
    }
}
