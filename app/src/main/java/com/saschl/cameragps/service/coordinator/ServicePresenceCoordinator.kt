package com.saschl.cameragps.service.coordinator

/** Lifecycle policy for CDM presence loss. Calls must be serialized by the service's command mutex. */
class ServicePresenceCoordinator(
    private val hasConnectedDevices: () -> Boolean,
    private val hasAlwaysOnDevices: suspend () -> Boolean,
    private val onShutdownRequested: () -> Unit,
) {
    private val disappearedDevices = mutableSetOf<String>()

    fun onDeviceAppeared(address: String) {
        disappearedDevices.remove(address.uppercase())
    }

    suspend fun onDeviceDisappeared(address: String) {
        val id = address.uppercase()
        disappearedDevices.add(id)
        stopIfDisconnectedAndAbsent(id)
    }

    suspend fun onDeviceDisconnected(address: String) {
        stopIfDisconnectedAndAbsent(address.uppercase())
    }

    private suspend fun stopIfDisconnectedAndAbsent(address: String) {
        // Intentionally retain the service for reconnection if CDM never reports disappearance.
        // TODO: Consider tracking Android 16+ BLE_DISAPPEARED and BT_DISCONNECTED separately,
        // retaining legacy handling on older Android and GATT state as the transmission authority.
        if (address !in disappearedDevices || hasConnectedDevices()) return
        if (hasAlwaysOnDevices()) return
        // GATT callbacks can update connection state while the database read suspends.
        if (!hasConnectedDevices()) {
            onShutdownRequested()
        }
    }
}
