package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteControlCoordinator

/**
 * Thin iOS-specific wrapper kept for backward compatibility.
 * All packet building now lives in shared [LocationPacketBuilder].
 */
internal object SonyLocationTransmissionUtils {
    fun hasTimeZoneDstFlag(value: ByteArray): Boolean {
        return RemoteControlCoordinator.hasTimeZoneDstFlag(value)
    }
}
