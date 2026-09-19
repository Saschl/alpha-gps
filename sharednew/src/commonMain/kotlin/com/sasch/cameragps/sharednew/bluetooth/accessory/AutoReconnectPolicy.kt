package com.sasch.cameragps.sharednew.bluetooth.accessory

/**
 * What the accessory session says about one camera.
 *
 * [Removed] and [Unknown] must stay distinct: the authorized set is empty until
 * AccessorySetupKit's async `activated` event lands, so a camera that drops two
 * seconds into a restoration relaunch reads as "not authorized" — and one
 * paired before AccessorySetupKit reads that way for ever.
 */
enum class AccessoryAuthorization {
    /** The session lists this accessory. */
    Authorized,

    /** The session reported it removed — the one positive signal. */
    Removed,

    /** Never migrated, not activated yet, or invalidated. Treated as usable. */
    Unknown,
}

/**
 * Whether a camera that just lost its link should be reconnected.
 *
 * Pure and tested because it gets no second chance: `didDisconnectPeripheral`
 * and `didFailToConnectPeripheral` are the only callers and nothing
 * re-evaluates them, so a wrong `false` strands the camera until the central
 * powers on again or the app restarts.
 */
object AutoReconnectPolicy {

    fun shouldReconnect(
        pairingRejected: Boolean,
        authorization: AccessoryAuthorization,
        appEnabled: Boolean,
        autoReconnectEnabled: Boolean,
        deviceEnabled: Boolean,
    ): Boolean = when {
        // Retrying would restart the pairing gate and loop the camera's prompt.
        pairingRejected -> false
        // CoreBluetooth may no longer see it; retrying is a storm against a
        // device that cannot answer. Never refuse on "absent from the
        // authorized set" — see [AccessoryAuthorization].
        authorization == AccessoryAuthorization.Removed -> false
        else -> appEnabled && autoReconnectEnabled && deviceEnabled
    }
}
