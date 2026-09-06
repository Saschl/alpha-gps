package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryPickerCompletion
import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.AccessorySetupKit.ASAccessory
import platform.AccessorySetupKit.ASAccessoryEvent
import platform.AccessorySetupKit.ASAccessoryEventTypeAccessoryAdded
import platform.AccessorySetupKit.ASAccessoryEventTypeAccessoryChanged
import platform.AccessorySetupKit.ASAccessoryEventTypeAccessoryRemoved
import platform.AccessorySetupKit.ASAccessoryEventTypeActivated
import platform.AccessorySetupKit.ASAccessoryEventTypeInvalidated
import platform.AccessorySetupKit.ASAccessoryEventTypeMigrationComplete
import platform.AccessorySetupKit.ASAccessoryEventTypePickerDidDismiss
import platform.AccessorySetupKit.ASAccessoryEventTypePickerDidPresent
import platform.AccessorySetupKit.ASAccessoryEventTypePickerSetupFailed
import platform.AccessorySetupKit.ASAccessorySession
import platform.AccessorySetupKit.ASErrorCodeActivationFailed
import platform.AccessorySetupKit.ASErrorCodeInvalidated
import platform.AccessorySetupKit.ASErrorCodeUserCancelled
import platform.AccessorySetupKit.ASErrorDomain
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * AccessorySetupKit mechanics: the [ASAccessorySession], its event stream, the
 * authorized-accessory snapshot and the two pickers. Migration policy lives in
 * [IosAccessoryCoordinator]; connections and device lists stay in [IosBluetoothController].
 *
 * Written in Kotlin rather than Swift because Kotlin/Native ships a generated
 * `platform.AccessorySetupKit` binding, so the Swift side can stay the thin shell
 * the project's architecture calls for.
 *
 * Main-thread confined: the session is activated on the main queue, so every
 * callback lands on the same dispatcher the rest of the controller uses.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAccessoryShell(
    private val onAccessoriesChanged: (IosAccessoryShell) -> Unit,
    private val onAccessoryAdded: (identifier: String, displayName: String?) -> Unit,
    private val onAccessoryRemoved: (identifier: String) -> Unit,
    private val onMigrationComplete: () -> Unit,
) : IosAccessoryCoordinator.Picker {

    /** Outcome of one picker presentation. */
    sealed interface PickerOutcome {
        /** The flow finished; any resulting accessory arrives through the callbacks. */
        data object Completed : PickerOutcome

        /** The person dismissed the picker without choosing anything. */
        data object Cancelled : PickerOutcome

        data class Failed(val message: String, val code: Long) : PickerOutcome
    }

    private val log = logging()

    private val session = ASAccessorySession()

    private val activated = CompletableDeferred<Unit>()
    private var activateRequested = false

    /** Uppercased bluetooth identifier -> accessory, refreshed from the session. */
    private val authorized = mutableMapOf<String, ASAccessory>()

    /**
     * The accessory chosen in the picker. AccessorySetupKit delivers
     * `accessoryAdded` BEFORE `pickerDidDismiss`, and acting on it while the
     * picker is still on screen would run the Sony handshake underneath it.
     */
    private var pendingAccessory: ASAccessory? = null

    // Discovery owns its handler until dismissal, even after a successful
    // showPicker callback. Capture each waiter so late callbacks cannot finish a retry.
    private var pickerCompletion: AccessoryPickerCompletion<PickerOutcome>? = null
    private var discoveryCustomizer: IosAccessoryDiscoveryCustomizer? = null

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    /**
     * Activate the session. Must be called before [showDiscoveryPicker],
     * [showMigrationPicker] or reading [authorizedIdentifiers].
     */
    fun activate() {
        if (activateRequested) return
        activateRequested = true
        log.i { "Activating the AccessorySetupKit session" }
        session.activateWithQueue(dispatch_get_main_queue()) { event -> handleEvent(event) }
    }

    /** Await the `activated` event. Returns false on timeout. */
    override suspend fun awaitActivated(): Boolean {
        activate()
        return withTimeoutOrNull(ACTIVATION_TIMEOUT_MS.milliseconds) { activated.await() } != null
    }

    // ---------------------------------------------------------------------------
    // Authorized accessories
    // ---------------------------------------------------------------------------

    override fun authorizedIdentifiers(): Set<String> = authorized.keys.toSet()

    fun isAuthorized(identifier: String): Boolean =
        authorized.containsKey(identifier.uppercase())

    fun displayName(identifier: String): String? =
        authorized[identifier.uppercase()]?.displayName

    // ---------------------------------------------------------------------------
    // Pickers
    // ---------------------------------------------------------------------------

    /**
     * Present the system picker so the person can authorize a new camera.
     * Must be driven by an explicit user action.
     */
    override suspend fun showDiscoveryPicker(): PickerOutcome {
        if (!awaitActivated()) return PickerOutcome.Failed("AccessorySetupKit did not activate", ASErrorCodeActivationFailed)
        val item = IosAccessoryPickerItems.discovery()
        log.i { "Presenting the discovery picker" }
        val customizer = IosAccessoryDiscoverySupport.create(session)
        discoveryCustomizer = customizer
        try {
            customizer?.start()
            return presentPicker(listOf(item), waitForDismissalOnSuccess = true)
        } finally {
            customizer?.stop()
            if (discoveryCustomizer === customizer) discoveryCustomizer = null
        }
    }

    /**
     * Present the migration flow for cameras that were paired before
     * AccessorySetupKit.
     *
     * The list must contain ONLY migration items. Maintainer testing observed
     * no visible system picker for these items;
     * mixing in a regular display item turns it back into a discovery picker and
     * migrates nothing unless a brand-new accessory is set up.
     */
    override suspend fun showMigrationPicker(candidates: List<PendingMigration>): PickerOutcome {
        if (candidates.isEmpty()) return PickerOutcome.Completed
        if (!awaitActivated()) return PickerOutcome.Failed("AccessorySetupKit did not activate", ASErrorCodeActivationFailed)

        val items = IosAccessoryPickerItems.migration(candidates)
        log.i { "Presenting the migration picker for ${items.size} camera(s)" }
        return presentPicker(items)
    }

    /**
     * Present the system's rename sheet for an authorized accessory.
     *
     * There is no way to set the name programmatically — `renameAccessory` takes
     * no string, it only displays Apple's own view — and its completion handler
     * reports an error, not the chosen name. The new name arrives afterwards as
     * an `accessoryChanged` event, which refreshes the authorized snapshot.
     * Renaming here is what keeps the app and the system accessory record in
     * step; an app-side text field would silently diverge from Settings.
     */
    suspend fun rename(identifier: String): Boolean {
        val accessory = authorized[identifier.uppercase()] ?: run {
            log.w { "Cannot rename $identifier: it is not an authorized accessory" }
            return false
        }
        val error = suspendCancellableCoroutine { continuation ->
            // No rename options: ASAccessoryRenameSSID is for Wi-Fi accessories.
            session.renameAccessory(accessory, options = 0uL) { error ->
                continuation.resume(error)
            }
        }
        if (error != null) log.w { "renameAccessory failed: ${error.localizedDescription}" }
        return error == null
    }

    /**
     * Remove the accessory from the system, which also drops the Bluetooth bond.
     * This is what makes "forget this camera" work without sending people to
     * Settings.
     */
    suspend fun remove(identifier: String): Boolean {
        val accessory = authorized[identifier.uppercase()] ?: run {
            // Nothing to revoke: the camera was never confirmed through the
            // picker, so its system pairing is not ours to remove and the user
            // has to do it in Settings.
            log.w { "Cannot remove $identifier: it is not an authorized accessory" }
            return false
        }
        val error = suspendCancellableCoroutine { continuation ->
            session.removeAccessory(accessory) { error -> continuation.resume(error) }
        }
        if (error != null) log.w { "removeAccessory failed: ${error.localizedDescription}" }
        return error == null
    }

    private suspend fun presentPicker(
        items: List<Any>,
        waitForDismissalOnSuccess: Boolean = false,
    ): PickerOutcome {
        val completion = AccessoryPickerCompletion<PickerOutcome>(
            PickerOutcome.Completed,
            waitForDismissalOnSuccess = waitForDismissalOnSuccess,
        )
        check(pickerCompletion == null) { "A picker is already pending" }
        pickerCompletion = completion
        try {
            session.showPickerForDisplayItems(items) { error ->
                val outcome = when {
                    error == null -> PickerOutcome.Completed
                    error.domain == ASErrorDomain && error.code == ASErrorCodeUserCancelled ->
                        PickerOutcome.Cancelled

                    else -> {
                        log.w { "Picker failed: ${error.localizedDescription} (${error.code})" }
                        PickerOutcome.Failed(error.localizedDescription, error.code)
                    }
                }
                log.i {
                    "Picker callback: $outcome; waitForDismissalOnSuccess=$waitForDismissalOnSuccess"
                }
                completion.onCompletion(outcome)
            }
            return completion.await()
        } finally {
            if (pickerCompletion === completion) pickerCompletion = null
        }
    }

    private fun handleEvent(event: ASAccessoryEvent?) {
        val type = event?.eventType ?: return
        discoveryCustomizer?.onEvent(event)
        when (type) {
            ASAccessoryEventTypeActivated -> {
                refreshAuthorized()
                log.i { "AccessorySetupKit activated with ${authorized.size} accessory(ies)" }
                activated.complete(Unit)
                onAccessoriesChanged(this)
            }

            ASAccessoryEventTypeMigrationComplete -> {
                val completion = pickerCompletion
                refreshAuthorized()
                log.i { "Migration complete; ${authorized.size} accessory(ies) authorized" }
                onAccessoriesChanged(this)
                onMigrationComplete()
                // Migration is now terminal even if dismissal/the showPicker
                // closure arrive later. Release ownership so recovery can create
                // the central and its power-on sweep can reconnect immediately.
                completion?.onMigrationComplete()
            }

            ASAccessoryEventTypeAccessoryAdded -> {
                // Held until pickerDidDismiss so setup does not run under the picker.
                pendingAccessory = event.accessory
                refreshAuthorized()
                onAccessoriesChanged(this)
            }

            ASAccessoryEventTypePickerDidDismiss -> {
                log.i { "Picker dismissed" }
                // Dismissal is also a terminal signal: do not leave controller
                // ownership (and the busy dialog) waiting on a late closure.
                val completion = pickerCompletion
                val accessory = pendingAccessory
                pendingAccessory = null
                if (accessory != null) {
                    val id = accessory.identifierString()
                    if (id != null) {
                        refreshAuthorized()
                        // Renaming can deliver accessoryChanged with a new
                        // snapshot after accessoryAdded. Save the final name.
                        val name = displayName(id) ?: accessory.displayName
                        log.i { "Accessory added: $id ($name)" }
                        onAccessoryAdded(id, name)
                    } else {
                        log.w { "Accessory added without a bluetooth identifier" }
                    }
                }
                completion?.onDismissed()
            }

            ASAccessoryEventTypeAccessoryRemoved -> {
                val id = event.accessory?.identifierString()
                refreshAuthorized()
                onAccessoriesChanged(this)
                if (id != null) {
                    log.i { "Accessory removed: $id" }
                    onAccessoryRemoved(id)
                }
            }

            ASAccessoryEventTypeAccessoryChanged -> {
                refreshAuthorized()
                onAccessoriesChanged(this)
            }

            ASAccessoryEventTypeInvalidated -> {
                // The session cannot be reused. Nothing here recreates it: that
                // would need a fresh object, and the app has no way to recover
                // the picker mid-flight anyway.
                log.e { "AccessorySetupKit session invalidated" }
                pickerCompletion?.onCompletion(
                    PickerOutcome.Failed("AccessorySetupKit session invalidated", ASErrorCodeInvalidated),
                )
                authorized.clear()
                onAccessoriesChanged(this)
            }

            ASAccessoryEventTypePickerSetupFailed -> log.w { "Accessory setup failed" }

            ASAccessoryEventTypePickerDidPresent -> log.i { "Picker presented" }

            else -> log.d { "Unhandled AccessorySetupKit event $type" }
        }
    }

    private fun refreshAuthorized() {
        authorized.clear()
        session.accessories.filterIsInstance<ASAccessory>().forEach { accessory ->
            accessory.identifierString()?.let { authorized[it] = accessory }
        }
    }

    private fun ASAccessory.identifierString(): String? =
        bluetoothIdentifier?.UUIDString?.uppercase()

    private companion object {
        const val ACTIVATION_TIMEOUT_MS = 5_000L
    }
}
