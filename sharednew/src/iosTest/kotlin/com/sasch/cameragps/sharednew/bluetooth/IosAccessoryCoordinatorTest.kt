package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.bluetooth.IosAccessoryShell.PickerOutcome
import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryPickerCompletion
import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.AccessorySetupKit.ASErrorCodePickerAlreadyActive
import platform.AccessorySetupKit.ASErrorCodePickerRestricted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)
class IosAccessoryCoordinatorTest {
    @Test
    fun launchAndExplainerKeepConnectionsRunningWithoutStartingMigration() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.evaluateMigration()
        assertEquals(
            listOf(CAMERA.mac),
            f.coordinator.migrationCandidates.value.map { it.identifier })
        assertTrue(f.coordinator.consumeAutoMigrationPrompt())
        assertFalse(f.coordinator.consumeAutoMigrationPrompt())
        assertTrue(f.connections.running)
        assertEquals(0, f.connections.releaseCalls)
        assertEquals(0, f.picker.migrationCalls)
        assertEquals(0L, testScheduler.currentTime)
        assertFalse(f.store.migrationDone)
    }

    @Test
    fun activationOrDatabaseFailureLeavesExistingConnectionsAndMigrationPending() = runTest {
        val f = Fixture(backgroundScope)
        f.picker.activated = false
        f.coordinator.evaluateMigration()
        assertFalse(f.store.migrationDone)
        assertEquals(0, f.store.loads)
        f.picker.activated = true
        f.store.failLoad = true
        f.coordinator.evaluateMigration()
        assertFalse(f.store.migrationDone)
        assertTrue(f.coordinator.migrationCandidates.value.isEmpty())
        assertTrue(f.connections.running)
        assertEquals(0, f.connections.releaseCalls)
        assertEquals(0, f.picker.migrationCalls)
    }

    @Test
    fun alreadyAuthorizedDevicesSettleWithoutOpeningPickerOrReleasingCentral() = runTest {
        val f = Fixture(backgroundScope)
        f.picker.authorized = setOf(CAMERA.mac)
        f.coordinator.evaluateMigration()
        assertTrue(f.store.migrationDone)
        assertFalse(f.coordinator.consumeAutoMigrationPrompt())
        assertEquals(0, f.picker.migrationCalls)
        assertEquals(0, f.connections.releaseCalls)
        assertEquals(1, f.connections.reconnectSweeps)
    }

    @Test
    fun migrationCompleteRestoresCentralEvenAfterUiCancellationWithoutDismissal() = runTest {
        val f = Fixture(backgroundScope)
        val completion = AccessoryPickerCompletion<PickerOutcome>(PickerOutcome.Completed)
        f.picker.migrate = {
            if (f.picker.migrationCalls == 1) {
                assertTrue(f.connections.running, "The first attempt keeps the cameras connected")
                PickerOutcome.Failed("Central still alive", ASErrorCodePickerRestricted)
            } else {
                completion.await()
            }
        }
        f.coordinator.evaluateMigration()
        val ui = launch { f.coordinator.presentMigrationPicker() }
        runCurrent()
        assertTrue(f.coordinator.migrationInProgress.value)
        assertEquals(1, f.picker.migrationCalls)
        assertFalse(f.connections.running)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, f.picker.migrationCalls)

        ui.cancel()
        runCurrent()
        assertTrue(f.coordinator.migrationInProgress.value)
        assertFalse(f.coordinator.presentMigrationPicker())
        assertFalse(f.coordinator.presentAccessoryPicker())
        assertEquals(0, f.picker.discoveryCalls)

        f.picker.authorized = setOf(CAMERA.mac)
        f.coordinator.handleMigrationComplete()
        runCurrent()
        assertTrue(f.store.migrationDone)
        assertFalse(f.connections.running) // Callback cannot restart under the operation's guard.
        completion.onMigrationComplete()
        runCurrent()
        assertTrue(f.connections.running)
        assertFalse(f.coordinator.migrationInProgress.value)
        assertFalse(f.coordinator.migrationError.value)
        assertTrue(f.coordinator.migrationCandidates.value.isEmpty())
        assertEquals(1, f.connections.starts)
        assertEquals(listOf(CAMERA), f.store.savedDevices)
    }

    @Test
    fun restrictedRetriesKeepExtendedTimingAndRestoreServiceWhenExhausted() = runTest {
        val f = Fixture(backgroundScope)
        val attemptTimes = mutableListOf<Long>()
        f.picker.migrate = {
            attemptTimes += testScheduler.currentTime
            PickerOutcome.Failed("Central still alive", ASErrorCodePickerRestricted)
        }
        f.coordinator.evaluateMigration()
        assertFalse(f.coordinator.presentMigrationPicker())
        // Asked once for free, then the release grace, then the retry backoff.
        assertEquals(listOf(0L, 10_000L) + (1L..8L).map { 10_000L + it * 5_000L }, attemptTimes)
        assertTrue(f.connections.running)
        assertEquals(1, f.connections.starts)
        assertFalse(f.coordinator.migrationInProgress.value)
        assertTrue(f.coordinator.migrationError.value)
        assertFalse(f.store.migrationDone)
        assertEquals(listOf(CAMERA), f.store.savedDevices)

        f.picker.migrate = {
            f.picker.authorized = setOf(CAMERA.mac)
            PickerOutcome.Completed
        }
        assertTrue(f.coordinator.presentMigrationPicker())
        assertFalse(f.coordinator.migrationError.value)
        assertTrue(f.store.migrationDone)
    }

    @Test
    fun cancellationAndNonRestrictedFailuresDoNotRetryOrDeleteCandidates() = runTest {
        for (outcome in listOf(
            PickerOutcome.Cancelled,
            PickerOutcome.Failed("Picker already active", ASErrorCodePickerAlreadyActive),
            PickerOutcome.Failed("Other failure", -1),
        )) {
            val f = Fixture(backgroundScope)
            f.picker.migrate = { outcome }
            f.coordinator.evaluateMigration()
            assertFalse(f.coordinator.presentMigrationPicker())
            assertEquals(1, f.picker.migrationCalls)
            assertTrue(f.connections.running)
            assertFalse(f.coordinator.migrationInProgress.value)
            // An already-active picker is reported like any other failure: the
            // runner refuses a double tap without calling the picker, so it means
            // the session still holds a picker and only a relaunch clears that.
            assertEquals(outcome is PickerOutcome.Failed, f.coordinator.migrationError.value)
            assertEquals(
                listOf(CAMERA.mac),
                f.coordinator.migrationCandidates.value.map { it.identifier })
            assertEquals(listOf(CAMERA), f.store.savedDevices)
            assertFalse(f.store.migrationDone)
        }
    }

    @Test
    fun pickerExceptionRestoresCentralAndReleasesOwnership() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        try {
            val f = Fixture(owner)
            f.coordinator.evaluateMigration()
            f.picker.migrate = { error("Native picker threw") }
            assertFailsWith<IllegalStateException> { f.coordinator.presentMigrationPicker() }
            assertTrue(f.connections.running)
            assertFalse(f.coordinator.migrationInProgress.value)
            assertTrue(f.coordinator.presentAccessoryPicker())
            assertEquals(1, f.picker.discoveryCalls)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun partialMigrationPreservesPendingDevicesAndTheirSettings() = runTest {
        val f = Fixture(backgroundScope)
        f.store.savedDevices = listOf(CAMERA, SECOND_CAMERA)
        f.picker.migrate = {
            f.picker.authorized = setOf(CAMERA.mac)
            PickerOutcome.Completed
        }
        f.coordinator.evaluateMigration()
        assertTrue(f.coordinator.presentMigrationPicker())
        assertFalse(f.store.migrationDone)
        assertEquals(
            listOf(SECOND_CAMERA.mac),
            f.coordinator.migrationCandidates.value.map { it.identifier })
        assertEquals(listOf(CAMERA, SECOND_CAMERA), f.store.savedDevices)
        assertTrue(f.connections.running)
    }

    @Test
    fun discoveryCanReopenWithoutEverInterruptingTheCameras() = runTest {
        val f = Fixture(backgroundScope)
        f.picker.authorized = setOf(CAMERA.mac)
        f.coordinator.evaluateMigration()
        f.picker.discover = {
            assertTrue(f.coordinator.centralCreationBlocked)
            assertFalse(f.coordinator.migrationInProgress.value)
            assertTrue(f.connections.running, "A picker the system accepts costs no connections")
            PickerOutcome.Completed
        }

        repeat(2) {
            assertTrue(f.coordinator.presentAccessoryPicker())
            assertTrue(f.connections.running)
            assertFalse(f.coordinator.centralCreationBlocked)
            assertEquals(listOf(CAMERA), f.store.savedDevices)
        }

        assertEquals(2, f.picker.discoveryCalls)
        assertEquals(0, f.picker.migrationCalls)
        assertEquals(0, f.connections.releaseCalls)
        assertEquals(0, f.connections.starts)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun discoveryOwnsCentralThroughGracePeriodCallbacksAndUiCancellation() = runTest {
        val f = Fixture(backgroundScope)
        val completion = AccessoryPickerCompletion<PickerOutcome>(PickerOutcome.Completed)
        f.picker.discover = {
            if (f.picker.discoveryCalls == 1) {
                assertTrue(f.connections.running, "The first attempt keeps the cameras connected")
                PickerOutcome.Failed("Central still alive", ASErrorCodePickerRestricted)
            } else {
                completion.await()
            }
        }
        f.coordinator.evaluateMigration()
        val ui = launch { f.coordinator.presentAccessoryPicker() }
        runCurrent()
        assertTrue(f.coordinator.centralCreationBlocked)
        assertFalse(f.coordinator.presentMigrationPicker())
        assertFalse(f.coordinator.presentAccessoryPicker())
        assertEquals(1, f.connections.releaseCalls)
        assertFalse(f.connections.running)
        assertEquals(1, f.picker.discoveryCalls)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, f.picker.discoveryCalls)

        ui.cancel()
        runCurrent()
        // Model accessory-added and migration-complete callbacks during the picker.
        f.connections.resumeConnections()
        f.picker.authorized = setOf(CAMERA.mac)
        f.coordinator.handleMigrationComplete()
        runCurrent()
        assertTrue(f.store.migrationDone)
        assertTrue(f.coordinator.centralCreationBlocked)
        assertFalse(f.connections.running)
        assertFalse(f.coordinator.presentAccessoryPicker())

        completion.onDismissed()
        runCurrent()
        assertFalse(f.coordinator.centralCreationBlocked)
        assertTrue(f.connections.running)
        assertEquals(1, f.connections.starts)
    }

    @Test
    fun discoveryRetriesRestrictedPickerUntilItSucceeds() = runTest {
        val f = Fixture(backgroundScope)
        val attemptTimes = mutableListOf<Long>()
        f.picker.discover = {
            assertTrue(f.coordinator.centralCreationBlocked)
            attemptTimes += testScheduler.currentTime
            // Only a refusal costs the cameras their connection.
            assertEquals(attemptTimes.size == 1, f.connections.running)
            if (attemptTimes.size < 3) {
                PickerOutcome.Failed(
                    "Central with global permissions still alive",
                    ASErrorCodePickerRestricted
                )
            } else PickerOutcome.Completed
        }
        assertTrue(f.coordinator.presentAccessoryPicker())
        assertEquals(listOf(0L, 10_000L, 15_000L), attemptTimes)
        assertEquals(2, f.connections.releaseCalls)
        assertTrue(f.connections.running)
        assertFalse(f.coordinator.centralCreationBlocked)
    }

    @Test
    fun discoveryExhaustionRestoresConnectionsAndAllowsManualRetry() = runTest {
        val f = Fixture(backgroundScope)
        val attemptTimes = mutableListOf<Long>()
        f.picker.discover = {
            attemptTimes += testScheduler.currentTime
            PickerOutcome.Failed("Central still alive", ASErrorCodePickerRestricted)
        }
        assertFalse(f.coordinator.presentAccessoryPicker())
        assertEquals(listOf(0L, 10_000L) + (1L..8L).map { 10_000L + it * 5_000L }, attemptTimes)
        assertTrue(f.connections.running)
        assertFalse(f.coordinator.centralCreationBlocked)
        assertFalse(f.coordinator.migrationError.value)
        assertEquals(listOf(CAMERA), f.store.savedDevices)

        f.picker.discover = { PickerOutcome.Completed }
        assertTrue(f.coordinator.presentAccessoryPicker())
        assertEquals(11, f.picker.discoveryCalls)
    }

    @Test
    fun discoveryCancellationAndOtherErrorsRestoreConnectionsWithoutRetrying() = runTest {
        for (outcome in listOf(
            PickerOutcome.Cancelled,
            PickerOutcome.Failed("Already active", ASErrorCodePickerAlreadyActive),
            PickerOutcome.Failed("Other error", -1),
        )) {
            val f = Fixture(backgroundScope)
            f.picker.discover = { outcome }
            assertFalse(f.coordinator.presentAccessoryPicker())
            assertEquals(1, f.picker.discoveryCalls)
            assertTrue(f.connections.running)
            assertFalse(f.coordinator.centralCreationBlocked)
            assertEquals(listOf(CAMERA), f.store.savedDevices)
        }
    }

    @Test
    fun discoveryExceptionRestoresCentralAndReleasesOwnership() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        try {
            val f = Fixture(owner)
            f.picker.discover = { error("Native discovery picker threw") }
            assertFailsWith<IllegalStateException> { f.coordinator.presentAccessoryPicker() }
            assertTrue(f.connections.running)
            assertFalse(f.coordinator.centralCreationBlocked)
            f.picker.discover = { PickerOutcome.Completed }
            assertTrue(f.coordinator.presentAccessoryPicker())
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun discoveryActivationFailureDoesNotReleaseCentral() = runTest {
        val f = Fixture(backgroundScope)
        f.picker.activated = false
        assertFalse(f.coordinator.presentAccessoryPicker())
        assertEquals(0, f.connections.releaseCalls)
        assertEquals(0, f.picker.discoveryCalls)
        assertTrue(f.connections.running)
        assertFalse(f.coordinator.centralCreationBlocked)
    }

    @Test
    fun aRestrictedPickerWithoutACentralSkipsTheReleaseGracePeriod() = runTest {
        val f = Fixture(backgroundScope)
        f.connections.running = false
        f.store.savedDevices = emptyList()
        val attemptTimes = mutableListOf<Long>()
        f.picker.discover = {
            attemptTimes += testScheduler.currentTime
            if (attemptTimes.size < 2) {
                PickerOutcome.Failed("Restricted for another reason", ASErrorCodePickerRestricted)
            } else PickerOutcome.Completed
        }
        assertTrue(f.coordinator.presentAccessoryPicker())
        // Nothing was deallocated, so the retry only pays the short backoff.
        assertEquals(listOf(0L, 5_000L), attemptTimes)
        assertTrue(f.connections.running)
        assertFalse(f.coordinator.centralCreationBlocked)
    }

    @Test
    fun migrationAsksTheSystemBeforeGivingUpAnyConnection() = runTest {
        val f = Fixture(backgroundScope)
        f.picker.migrate = {
            assertTrue(f.connections.running, "Nothing is torn down before the system objects")
            f.picker.authorized = setOf(CAMERA.mac)
            PickerOutcome.Completed
        }
        f.coordinator.evaluateMigration()
        assertTrue(f.coordinator.presentMigrationPicker())
        assertEquals(0L, testScheduler.currentTime, "An accepted picker waits for nothing")
        assertEquals(0, f.connections.releaseCalls)
        assertEquals(0, f.connections.starts)
        assertTrue(f.connections.running)
        assertTrue(f.store.migrationDone)
    }

    @Test
    fun aRestrictedPickerReleasesTheCentralAndWaitsOutItsDeallocation() = runTest {
        val f = Fixture(backgroundScope)
        val attemptTimes = mutableListOf<Long>()
        f.picker.discover = {
            attemptTimes += testScheduler.currentTime
            if (attemptTimes.size == 1) {
                assertTrue(f.connections.running)
                PickerOutcome.Failed("Central still alive", ASErrorCodePickerRestricted)
            } else {
                // The refusal is the authoritative answer about the central, so
                // the retry pays the full deallocation grace before asking again.
                assertFalse(f.connections.running)
                PickerOutcome.Completed
            }
        }
        assertTrue(f.coordinator.presentAccessoryPicker())
        assertEquals(listOf(0L, 10_000L), attemptTimes)
        assertEquals(1, f.connections.releaseCalls)
        assertTrue(f.connections.running)
    }

    private class Fixture(scope: CoroutineScope) {
        val store = FakeStore()
        val picker = FakePicker()
        val connections = FakeConnections { coordinator.centralCreationBlocked }
        val coordinator: IosAccessoryCoordinator = IosAccessoryCoordinator(
            controllerScope = scope,
            accessorySession = picker,
            store = store,
            connections = connections,
            onDevicesChanged = {},
        )
    }

    private class FakePicker : IosAccessoryCoordinator.Picker {
        var activated = true
        var authorized = emptySet<String>()
        var migrationCalls = 0
        var discoveryCalls = 0
        var migrate: suspend () -> PickerOutcome = { PickerOutcome.Completed }
        var discover: suspend () -> PickerOutcome = { PickerOutcome.Completed }
        override suspend fun awaitActivated() = activated
        override fun authorizedIdentifiers() = authorized
        override suspend fun showMigrationPicker(candidates: List<PendingMigration>): PickerOutcome {
            migrationCalls++
            return migrate()
        }

        override suspend fun showDiscoveryPicker(): PickerOutcome {
            discoveryCalls++
            return discover()
        }
    }

    private class FakeStore : IosAccessoryCoordinator.Store {
        override var migrationDone = false
        override var savedDevices = listOf(CAMERA)
        var loads = 0
        var failLoad = false
        override suspend fun loadSavedDevices() {
            loads++
            if (failLoad) error("Protected database unavailable")
        }

        override suspend fun sync() = Unit
    }

    private class FakeConnections(private val centralCreationBlocked: () -> Boolean) :
        IosAccessoryCoordinator.Connections {
        var running = true
        var starts = 0
        var releaseCalls = 0
        var reconnectSweeps = 0
        override fun releaseCentral(): Boolean {
            assertTrue(centralCreationBlocked(), "Block callbacks before releasing the central")
            releaseCalls++
            val wasRunning = running
            running = false
            return wasRunning
        }

        override fun resumeConnections(reconnectExisting: Boolean) {
            // Mirrors the controller's guard, so early callback requests stay blocked.
            if (centralCreationBlocked()) return
            if (!running) {
                running = true
                starts++
            } else if (reconnectExisting) {
                reconnectSweeps++
            }
        }
    }

    private companion object {
        val CAMERA = CameraDevice(
            mac = "1D2B4E1C-0000-4000-8000-0000000000A1",
            deviceName = "ILCE-7M4",
            remoteControlEnabled = true,
            handshakeDelayMs = 500,
        )
        val SECOND_CAMERA =
            CAMERA.copy(mac = "1D2B4E1C-0000-4000-8000-0000000000B2", deviceEnabled = false)
    }
}
