package com.sasch.cameragps.sharednew.bluetooth.coordinator

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase

/**
 * Events emitted by shared BLE coordinators. Platform hosts (Android service / iOS controller)
 * collect these and map them to platform-specific side effects (UI updates, sounds, notifications).
 */
sealed interface BleSessionEvent {
    /** Remote feature became active on the camera. */
    data class RemoteFeatureActivated(val identifier: String) : BleSessionEvent

    /** Remote feature became inactive on the camera. */
    data class RemoteFeatureDeactivated(val identifier: String) : BleSessionEvent

    /** BLE session phase changed for a device. */
    data class PhaseChanged(
        val identifier: String,
        val phase: BleSessionPhase,
        val remoteActive: Boolean? = null,
    ) : BleSessionEvent

    /** BLE handshake finished — ready to transmit location and probe remote. */
    data class HandshakeComplete(val identifier: String) : BleSessionEvent
}


