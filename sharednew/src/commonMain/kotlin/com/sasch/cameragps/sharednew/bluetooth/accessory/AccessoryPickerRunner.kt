package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Main-thread confined picker ownership. UI cancellation must not abandon a native picker. */
internal class AccessoryPickerRunner(private val scope: CoroutineScope) {
    private var busy = false
    private val migrating = MutableStateFlow(false)
    val migrationInProgress: StateFlow<Boolean> = migrating

    suspend fun run(
        migration: Boolean,
        recover: () -> Unit = {},
        picker: suspend () -> Boolean,
    ): Boolean {
        if (busy) return false
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            busy = true
            migrating.value = migration
            try {
                picker()
            } finally {
                migrating.value = false
                try {
                    recover()
                } finally {
                    busy = false
                }
            }
        }.await()
    }
}
