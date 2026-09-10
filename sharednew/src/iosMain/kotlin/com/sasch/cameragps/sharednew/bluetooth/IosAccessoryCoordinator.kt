package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.IosAccessoryShell.PickerOutcome
import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryMigrationPlanner
import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryPickerRunner
import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.AccessorySetupKit.ASErrorCodePickerRestricted

/**
 * Foreground accessory setup policy: migration state, picker ownership, retries,
 * preferences. Construction is inert; launch evaluation only
 * prepares the prompt and never interrupts existing connections.
 *
 * The controller owns the central and its restoration wiring. Both collaborators
 * run on the same Main.immediate scope; UI cancellation does not cancel a picker.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAccessoryCoordinator(
    private val controllerScope: CoroutineScope,
    private val accessorySession: Picker,
    private val store: Store,
    private val connections: Connections,
    private val onDevicesChanged: () -> Unit,
) {
    /** Native session mechanics needed by setup, implemented by IosAccessoryShell. */
    interface Picker {
        suspend fun awaitActivated(): Boolean
        fun authorizedIdentifiers(): Set<String>
        suspend fun showMigrationPicker(candidates: List<PendingMigration>): PickerOutcome
        suspend fun showDiscoveryPicker(): PickerOutcome
    }

    /** Migration persistence; the production adapter shares the device repository. */
    interface Store {
        var migrationDone: Boolean
        val savedDevices: Collection<CameraDevice>
        suspend fun loadSavedDevices()
        suspend fun sync()
    }

    interface Connections {
        /** Stop/release the central; return whether there was one to release. */
        fun releaseCentral(): Boolean

        /** Create the central if absent; optionally sweep saved devices if already running. */
        fun resumeConnections(reconnectExisting: Boolean = false)
    }

    private val logging = logging()

    /**
     * Saved cameras that still need migration. Existing connections stay usable
     * until the user confirms migration in the foreground UI.
     */
    private val _migrationCandidates = MutableStateFlow<List<PendingMigration>>(emptyList())
    val migrationCandidates: StateFlow<List<PendingMigration>> = _migrationCandidates

    /**
     * The last migration attempt failed after exhausting its retries. Drives an
     * error dialog offering another try — the retries handle the deallocation
     * race, but if they run out the user has to be told rather than left with a
     * sheet that silently never appeared.
     */
    private val _migrationError = MutableStateFlow(false)
    val migrationError: StateFlow<Boolean> = _migrationError

    fun clearMigrationError() {
        _migrationError.value = false
    }

    /**
     * A migration attempt is running. The flow spends seconds releasing the
     * central and retrying a restricted picker before iOS shows anything, so the
     * UI keeps its dialog up and busy rather than looking like nothing happened.
     */
    private val pickerRunner = AccessoryPickerRunner(controllerScope)
    private val _migrationInProgress = MutableStateFlow(false)
    val migrationInProgress: StateFlow<Boolean> = _migrationInProgress

    /** Both picker flows hold this guard until their native operation finishes. */
    var centralCreationBlocked: Boolean = false
        private set

    /** Guards the automatic migration sheet to one attempt per launch. */
    private var migrationAutoAttempted = false

    /**
     * Work out whether any saved camera still predates AccessorySetupKit. Runs on
     * every launch until migration is recorded as done, because a user can have
     * saved devices from several older versions.
     */
    suspend fun evaluateMigration() {
        val done = store.migrationDone
        logging.i {
            "Migration check: done=$done, active=${migrationInProgress.value}"
        }
        if (done) {
            logging.i { "Migration skipped: already recorded as complete" }
            return
        }
        if (centralCreationBlocked) {
            logging.i { "Migration skipped: an operation is already running" }
            return
        }
        if (!accessorySession.awaitActivated()) {
            // Leave the launch central running for existing connections. Retry
            // migration on a later launch once AccessorySetupKit is available.
            logging.e { "AccessorySetupKit did not activate; leaving migration pending" }
            return
        }
        val read = withDatabase("migration check") {
            store.loadSavedDevices()
        }
        if (!read) {
            // An empty device list from a failed read would look like "nothing to
            // migrate" and permanently record migration as done, stranding every
            // saved camera. Leave it pending and retry on the next launch.
            logging.e { "Could not read saved devices; leaving migration pending" }
            return
        }
        val candidates = AccessoryMigrationPlanner.planMigrations(
            saved = store.savedDevices,
            authorized = accessorySession.authorizedIdentifiers(),
        )
        _migrationCandidates.value = candidates
        if (candidates.isEmpty()) {
            // Nothing saved, or everything already authorized. Never ask again.
            logging.i { "Migration skipped: no pending candidates" }
            finishMigration()
        } else {
            logging.i { "${candidates.size} saved camera(s) await AccessorySetupKit authorization" }
        }
        onDevicesChanged()
    }

    /**
     * Whether to raise the migration explainer on this launch, consuming the
     * one attempt it gets.
     *
     * Foreground launches explain migration before interrupting connections.
     *
     * Once per launch, so declining does not trap the user in a loop; the card
     * in the device list stays available for a manual retry.
     */
    fun consumeAutoMigrationPrompt(): Boolean {
        if (migrationAutoAttempted) return false
        if (_migrationCandidates.value.isEmpty()) return false
        migrationAutoAttempted = true
        logging.i { "Raising the migration explainer" }
        return true
    }

    /**
     * Migrate saved cameras after the user confirms in the foreground UI.
     * Migration-only items have shown no system picker in maintainer testing.
     */
    suspend fun presentMigrationPicker(): Boolean = pickerRunner.run {
        migrationAutoAttempted = true
        val candidates = _migrationCandidates.value
        if (candidates.isEmpty()) return@run true
        centralCreationBlocked = true
        _migrationInProgress.value = true
        try {
            _migrationError.value = false

            val outcome = showPickerWithRetries("Migration") {
                accessorySession.showMigrationPicker(candidates)
            }
            logging.i { "Migration picker finished: $outcome" }
            recomputeMigrationCandidates()

            if (outcome is PickerOutcome.Failed) {
                // Retries are exhausted. Surface it and offer another attempt: the
                // cause is usually a CBCentralManager that had not been deallocated
                // yet, which a second try normally clears. An already-active picker
                // is reported too — the runner refuses a double tap without calling
                // the picker at all, so it means the session still holds a picker
                // from an earlier attempt, and only relaunching clears that. The
                // dialog says so; a migration that succeeds needs no restart, and
                // the maintainer confirmed the app works straight afterwards.
                logging.w { "Migration failed after retries: ${outcome.message}" }
                _migrationError.value = true
            }
            outcome is PickerOutcome.Completed
        } finally {
            // Release the guard before recreating the central. Its power-on
            // callback reconnects saved cameras, including newly migrated ones.
            _migrationInProgress.value = false
            centralCreationBlocked = false
            connections.resumeConnections()
        }
    }

    /**
     * Show the AccessorySetupKit picker so the user can authorize a new camera.
     * This is the replacement for the old in-app scan list.
     */
    suspend fun presentAccessoryPicker(): Boolean = pickerRunner.run {
        // An unavailable session cannot present anything; retain existing connections.
        if (!accessorySession.awaitActivated()) {
            logging.e { "AccessorySetupKit did not activate; leaving connections running" }
            return@run false
        }
        centralCreationBlocked = true
        try {
            val outcome = showPickerWithRetries("Discovery") {
                accessorySession.showDiscoveryPicker()
            }
            if (outcome is PickerOutcome.Failed) {
                // The pairing screen only stops its spinner, so this log is the
                // one trace of a picker the user never got to see.
                logging.e { "Discovery picker failed: ${outcome.message} (${outcome.code})" }
            } else {
                logging.i { "Discovery picker finished: $outcome" }
            }
            outcome is PickerOutcome.Completed
        } finally {
            // Accessory-added and migration-complete callbacks cannot recreate the
            // central until the picker releases ownership, even if the UI went away.
            centralCreationBlocked = false
            connections.resumeConnections()
        }
    }

    /** Debug only: forget that migration was done so the flow can be re-tested. */
    fun resetAccessoryMigrationForTesting() {
        logging.i { "Resetting AccessorySetupKit migration state" }
        store.migrationDone = false
        migrationAutoAttempted = false
        controllerScope.launch { evaluateMigration() }
    }

    /**
     * Ask for the picker first and only tear the central down if the system
     * actually refuses it.
     *
     * `ASErrorCodePickerRestricted` is the authoritative answer to "does this
     * central block the picker", and it costs one immediate error to get. An
     * install without the legacy global Bluetooth grant is never refused, so it
     * keeps its cameras connected right through setup; one that is refused loses
     * nothing but the failed attempt. Releasing a CBCentralManager is not
     * instantaneous under Kotlin/Native, so the first release is followed by the
     * full deallocation grace, later retries by the shorter backoff.
     */
    private suspend fun showPickerWithRetries(
        operation: String,
        showPicker: suspend () -> PickerOutcome,
    ): PickerOutcome {
        var outcome = showPicker()
        repeat(PICKER_RESTRICTED_ATTEMPTS - 1) { attempt ->
            val restricted = outcome as? PickerOutcome.Failed
            if (restricted?.code != ASErrorCodePickerRestricted) return outcome
            // Released again on every retry: a callback may have brought one up.
            val wait =
                if (connections.releaseCentral()) CENTRAL_RELEASE_GRACE_MS else PICKER_RETRY_DELAY_MS
            logging.w {
                "$operation picker restricted (attempt ${attempt + 1}/$PICKER_RESTRICTED_ATTEMPTS); " +
                        "waiting $wait ms before retry"
            }
            delay(wait)
            outcome = showPicker()
        }
        return outcome
    }

    fun handleMigrationComplete() {
        controllerScope.launch { recomputeMigrationCandidates() }
    }

    suspend fun recomputeMigrationCandidates() {
        withDatabase("migration recheck") { store.sync() }
        val authorized = accessorySession.authorizedIdentifiers()
        // Both sides logged verbatim: if AccessorySetupKit ever hands back an
        // identifier that differs from the CBPeripheral UUID we stored, the
        // candidate list can never drain and this is the only way to see it.
        logging.i {
            "Migration recheck: authorized=$authorized " +
                    "saved=${store.savedDevices.map { it.mac }}"
        }
        val remaining = AccessoryMigrationPlanner.planMigrations(
            saved = store.savedDevices,
            authorized = authorized,
        )
        _migrationCandidates.value = remaining
        if (remaining.isEmpty()) {
            finishMigration()
        } else {
            logging.i { "${remaining.size} camera(s) still await authorization" }
        }
        onDevicesChanged()
    }

    /** Migration is settled: record it and bring the central up for good. */
    private fun finishMigration() {
        store.migrationDone = true
        _migrationCandidates.value = emptyList()
        logging.i { "AccessorySetupKit migration settled" }
        // When the central is created here, its own power-on runs the reconnect
        // sweep. Sweeping now would fire retrieve/connect before PoweredOn, where
        // CoreBluetooth drops both.
        connections.resumeConnections(reconnectExisting = true)
    }

    private suspend fun withDatabase(what: String, block: suspend () -> Unit): Boolean =
        runCatching { block() }
            .onFailure { logging.e(it, msg = { "Database work failed during $what" }) }
            .isSuccess

    private companion object {
        /**
         * How long to wait after releasing the central, so Kotlin/Native's
         * collector can actually deallocate the CBCentralManager. The picker is
         * refused while one is alive.
         */
        private const val CENTRAL_RELEASE_GRACE_MS = 10_000L

        /**
         * `ASErrorCodePickerRestricted` means the manager was still alive when the
         * picker was asked for. Deallocation is not deterministic, so back off and
         * try again rather than failing the migration outright.
         */
        private const val PICKER_RESTRICTED_ATTEMPTS = 10
        private const val PICKER_RETRY_DELAY_MS = 5_000L

    }
}
