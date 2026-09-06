package com.sasch.cameragps.sharednew.ui.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread confined; reading the instructions never starts platform discovery. */
class PairingPreparationState {
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    suspend fun search(openPicker: suspend () -> Boolean): Boolean {
        if (_isSearching.value) return false
        _isSearching.value = true
        try {
            return openPicker()
        } finally {
            _isSearching.value = false
        }
    }
}
