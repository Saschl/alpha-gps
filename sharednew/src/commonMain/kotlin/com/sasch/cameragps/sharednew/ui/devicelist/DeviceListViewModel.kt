package com.sasch.cameragps.sharednew.ui.devicelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.remote.BulbTimerState
import com.sasch.cameragps.sharednew.remote.wifi.WifiRemotePhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Platform inputs for the shared device-list state. Both implementations are
 * thin wrappers over the app-scoped graph (AppServices on Android,
 * IosBluetoothController on iOS).
 */
interface DeviceListDataSource {
    val sessions: StateFlow<Map<String, CameraSession>>
    val transmissionActive: StateFlow<Boolean>
    val deviceSettings: Flow<List<CameraDevice>>
}

/**
 * Derived per-device state for a list row. Platforms iterate their own identity
 * lists (CDM associations / discovered peripherals) and look rows up by
 * uppercased identifier; a missing entry means "no state yet" and renders as
 * all-off defaults.
 */
data class DeviceListItem(
    val identifier: String,
    /**
     * A name a person chose, or null when the row should keep the identity name
     * its platform already has (a CDM association name, a peripheral name).
     */
    val customName: String?,
    val isAlwaysOnEnabled: Boolean,
    val isTransmissionActive: Boolean,
    val isRemoteFeatureActive: Boolean,
    val isShutterActive: Boolean,
    val locationDisabledByCamera: Boolean = false,
    val bulbTimer: BulbTimerState = BulbTimerState(),
    val canStartBulbTimer: Boolean = false,
)

/**
 * Shared device-list presentation state: the single derivation point of
 * per-device UI state from the session registry, the global transmission gate
 * and the persisted device settings.
 */
class DeviceListViewModel(dataSource: DeviceListDataSource) : ViewModel() {

    val items: StateFlow<Map<String, DeviceListItem>> = combine(
        dataSource.sessions,
        dataSource.transmissionActive,
        dataSource.deviceSettings,
    ) { sessions, transmissionActive, settings ->
        val settingsByMac = settings.associateBy { it.mac.uppercase() }
        (sessions.keys + settingsByMac.keys).associateWith { identifier ->
            val session = sessions[identifier]
            val persisted = settingsByMac[identifier]
            val wifiPhase = session?.wifiRemote?.phase
            val bluetoothAvailable = wifiPhase == WifiRemotePhase.Idle || wifiPhase == WifiRemotePhase.Failed
            val bulb = session?.bulbTimer?.takeIf { bluetoothAvailable } ?: BulbTimerState()
            DeviceListItem(
                identifier = identifier,
                customName = persisted?.takeIf { it.deviceNameIsCustom }?.deviceName
                    ?.takeUnless { it.isBlank() },
                isAlwaysOnEnabled = persisted?.alwaysOnEnabled == true,
                isTransmissionActive =
                    session?.isLocationReady == true && transmissionActive,
                isRemoteFeatureActive = session?.remoteFeatureActive == true,
                isShutterActive = session?.shutterSequenceActive == true,
                bulbTimer = bulb,
                canStartBulbTimer = bluetoothAvailable && persisted?.deviceEnabled != false &&
                        session?.remoteFeatureActive == true && !session.shutterSequenceActive && !bulb.active,
                locationDisabledByCamera = session?.locationDisabledByCamera == true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
