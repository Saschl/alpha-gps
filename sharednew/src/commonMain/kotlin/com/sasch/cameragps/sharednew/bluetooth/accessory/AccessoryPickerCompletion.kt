package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.CompletableDeferred

/** One native picker attempt; terminal events and its closure may arrive in either order. */
internal class AccessoryPickerCompletion<T>(private val completed: T) {
    private val result = CompletableDeferred<T>()

    fun onCompletion(value: T) {
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
