package com.sasch.cameragps.sharednew.ui.pairing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairingPreparationStateTest {
    @Test
    fun instructionsStartIdleAndOnlyExplicitSearchOpensPicker() = runTest {
        val state = PairingPreparationState()
        var calls = 0
        assertFalse(state.isSearching.value)
        runCurrent()
        assertEquals(0, calls)
        assertTrue(state.search {
            calls++
            assertTrue(state.isSearching.value)
            true
        })
        assertEquals(1, calls)
        assertFalse(state.isSearching.value)
    }

    @Test
    fun successfulPairingAllowsAddingAnotherCamera() = runTest {
        val state = PairingPreparationState()
        var calls = 0

        repeat(2) {
            assertTrue(state.search {
                calls++
                assertTrue(state.isSearching.value)
                true
            })
            assertFalse(state.isSearching.value)
        }

        assertEquals(2, calls)
    }

    @Test
    fun repeatedTapsDoNotOpenAnotherPickerAndFailureAllowsRetry() = runTest {
        val state = PairingPreparationState()
        val result = CompletableDeferred<Boolean>()
        val firstSearch = async { state.search { result.await() } }
        runCurrent()
        assertTrue(state.isSearching.value)
        assertFalse(state.search { error("Second picker must not open") })
        assertTrue(state.isSearching.value)
        result.complete(false)
        assertFalse(firstSearch.await())
        assertFalse(state.isSearching.value)
        assertTrue(state.search { true })
    }

    @Test
    fun exceptionReleasesSearchingState() = runTest {
        val state = PairingPreparationState()
        assertFailsWith<IllegalStateException> { state.search { error("Picker failed") } }
        assertFalse(state.isSearching.value)
    }

    @Test
    fun cancellationReleasesSearchingState() = runTest {
        val state = PairingPreparationState()
        val search = launch { state.search { awaitCancellation() } }
        runCurrent()
        assertTrue(state.isSearching.value)
        search.cancel()
        runCurrent()
        assertFalse(state.isSearching.value)
    }
}
