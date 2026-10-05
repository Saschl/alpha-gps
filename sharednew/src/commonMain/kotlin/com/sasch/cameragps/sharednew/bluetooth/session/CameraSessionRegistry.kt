package com.sasch.cameragps.sharednew.bluetooth.session

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemotePhase
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteState
import com.sasch.cameragps.sharednew.remote.wifi.WifiShutdownStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * State of one camera session. Replaces the Android `CameraConnectionConfig`
 * state fields and the iOS `PeripheralSession`/`PeripheralPhase` pair.
 */
data class CameraSession(
    /** Uppercased MAC address (Android) / peripheral UUID string (iOS). */
    val identifier: String,
    val phase: BleSessionPhase = BleSessionPhase.Connecting,
    val remoteFeatureActive: Boolean = false,
    /** A shutter sequence is currently running (UI feedback on the shutter button). */
    val shutterSequenceActive: Boolean = false,
    /** Consecutive auth-error retries (iOS pairing); reset on any success. */
    val pairingRetryCount: Int = 0,
    /** One-shot retry guard for the config read (intermittent GATT 133 on Android). */
    val hasRetriedConfigRead: Boolean = false,
    /** Camera-reported status for the UI only; some cameras may report disabled during startup. */
    val locationDisabledByCamera: Boolean = false,
    val autoTimeCorrection: CameraSettingState = CameraSettingState(),
    val autoAreaAdjustment: CameraSettingState = CameraSettingState(),
    /** Wi-Fi control has its own lifecycle and may outlive a BLE disconnection. */
    val wifiRemote: WifiRemoteState = WifiRemoteState(),
) {
    fun autoCorrectionSetting(setting: CameraAutoCorrectionSetting): CameraSettingState =
        when (setting) {
            CameraAutoCorrectionSetting.Time -> autoTimeCorrection
            CameraAutoCorrectionSetting.Area -> autoAreaAdjustment
        }

    val isLocationReady: Boolean
        get() = phase == BleSessionPhase.Transmitting
}

/**
 * Per-device session registry, exposed as a StateFlow for both platform UIs.
 * All mutations happen on the orchestrator's confined dispatcher.
 */
class CameraSessionRegistry {

    private val _sessions = MutableStateFlow<Map<String, CameraSession>>(emptyMap())
    val sessions: StateFlow<Map<String, CameraSession>> = _sessions

    /** Create-or-update. Only connection setup should create sessions. */
    fun upsert(identifier: String, transform: (CameraSession) -> CameraSession = { it }) {
        val id = identifier.uppercase()
        val current = _sessions.value[id] ?: CameraSession(id)
        _sessions.value = _sessions.value + (id to transform(current))
    }

    /** Update only if a session exists — phase/remote updates must not resurrect removed sessions. */
    fun updateIfPresent(identifier: String, transform: (CameraSession) -> CameraSession) {
        val id = identifier.uppercase()
        val existing = _sessions.value[id] ?: return
        _sessions.value = _sessions.value + (id to transform(existing))
    }

    fun remove(identifier: String) {
        _sessions.value = _sessions.value - identifier.uppercase()
    }

    /** Forget BLE state, preserving a live Wi-Fi attempt or session for the selected device. */
    fun markBleDisconnected(identifier: String) {
        val id = identifier.uppercase()
        val current = _sessions.value[id] ?: return
        if (current.wifiRemote.phase == WifiRemotePhase.Idle) {
            remove(id)
        } else {
            _sessions.value = _sessions.value + (id to current.copy(
                phase = BleSessionPhase.Disconnected,
                remoteFeatureActive = false,
                shutterSequenceActive = false,
                locationDisabledByCamera = false,
                autoTimeCorrection = CameraSettingState(),
                autoAreaAdjustment = CameraSettingState(),
            ))
        }
    }

    /** Retain shutdown feedback after closure; otherwise remove rows with no remaining transport. */
    fun updateWifiRemote(identifier: String, state: WifiRemoteState) {
        val id = identifier.uppercase()
        val current = _sessions.value[id] ?: return
        if (state.phase == WifiRemotePhase.Idle && current.phase == BleSessionPhase.Disconnected &&
            state.wifiShutdown == WifiShutdownStatus.NotRequested
        ) {
            remove(id)
        } else {
            _sessions.value = _sessions.value + (id to current.copy(wifiRemote = state))
        }
    }

    fun clear() {
        _sessions.value = emptyMap()
    }

    fun get(identifier: String): CameraSession? = _sessions.value[identifier.uppercase()]

    /** Devices whose handshake completed and are receiving location packets. */
    fun readyIdentifiers(): Set<String> =
        _sessions.value.filterValues { it.isLocationReady }.keys

    /** Number of devices with a live connection (any phase past connecting, minus errors). */
    fun activeCount(): Int = _sessions.value.values.count { it.phase.isActiveConnection() }

    private fun BleSessionPhase.isActiveConnection(): Boolean = when (this) {
        BleSessionPhase.Disconnected,
        BleSessionPhase.Connecting,
        BleSessionPhase.Error,
            -> false

        else -> true
    }
}
