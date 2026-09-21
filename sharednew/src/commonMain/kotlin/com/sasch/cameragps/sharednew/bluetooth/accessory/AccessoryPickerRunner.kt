package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async

/** Main-thread confined picker ownership. UI cancellation must not abandon a native picker. */
internal class AccessoryPickerRunner(private val scope: CoroutineScope) {
    private var busy = false

    suspend fun run(picker: suspend () -> Boolean): Boolean {
        if (busy) return false
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            busy = true
            try {
                picker()
            } finally {
                busy = false
            }
        }.await()
    }
}
