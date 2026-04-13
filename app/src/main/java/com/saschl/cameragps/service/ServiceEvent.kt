package com.saschl.cameragps.service

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase

/**
 * Events emitted by coordinators. The service collects these and handles side effects
 * (state updates, sounds, notifications, shutdown). Coordinators never call back into the service.
 */
sealed interface ServiceEvent {
    /** BLE session phase changed for a device. */
    data class PhaseChanged(
        val address: String,
        val phase: BleSessionPhase,
        val remoteActive: Boolean? = null,
    ) : ServiceEvent

    /** BLE handshake finished — ready to transmit location and probe remote. */
    data class HandshakeComplete(val address: String) : ServiceEvent

    /** Device session fully cleared (disconnected). */
    data class DeviceCleared(val address: String) : ServiceEvent

    /** All device sessions cleared (service destroy). */
    data object AllDevicesCleared : ServiceEvent

    /** First GPS fix acquired this session. */
    data object FirstLocationAcquired : ServiceEvent

    /** Periodic tick fired but no location is available. */
    data object LocationInvalid : ServiceEvent

    /** A coordinator requests the service to stop. */
    data class RequestShutdown(val startId: Int?) : ServiceEvent

    data class RemoteFeatureActivated(val address: String) : ServiceEvent

    data class RemoteFeatureDeactivated(val address: String) : ServiceEvent
}
