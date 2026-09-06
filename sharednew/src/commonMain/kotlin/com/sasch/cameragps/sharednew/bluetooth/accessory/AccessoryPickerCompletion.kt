package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.CompletableDeferred

/** One native picker attempt; callbacks and dismissal can arrive in either order. */
internal class AccessoryPickerCompletion<T>(
    private val completed: T,
    private val waitForDismissalOnSuccess: Boolean = false,
) {
    private val result = CompletableDeferred<T>()

    fun onCompletion(value: T) {
        // A successful showPicker callback must not tear down a visible
        // discovery picker's event handler. Only dismissal ends that lifetime.
        // Migration may have no visible picker, so it keeps callback completion.
        if (waitForDismissalOnSuccess && value == completed) return
        result.complete(value)
    }

    fun onMigrationComplete() {
        result.complete(completed)
    }

    fun onDismissed() {
        result.complete(completed)
    }

    suspend fun await(): T = result.await()
}
