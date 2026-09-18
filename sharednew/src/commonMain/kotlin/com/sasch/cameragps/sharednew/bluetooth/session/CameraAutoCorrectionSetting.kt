package com.sasch.cameragps.sharednew.bluetooth.session

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import kotlinx.coroutines.flow.StateFlow

enum class CameraAutoCorrectionSetting(val characteristicUuid: String) {
    Time(SonyBluetoothConstants.AUTO_TIME_CORRECTION_UUID),
    Area(SonyBluetoothConstants.AUTO_AREA_ADJUSTMENT_UUID);

    companion object {
        fun fromUuid(uuid: String): CameraAutoCorrectionSetting? =
            entries.firstOrNull { it.characteristicUuid.equals(uuid, ignoreCase = true) }
    }
}

/** A camera-owned setting. Null means unknown, never an assumed off value. */
data class CameraSettingState(
    val supported: Boolean? = null,
    val enabled: Boolean? = null,
    val pending: Boolean = false,
    val failed: Boolean = false,
)

interface CameraAutoCorrectionControls {
    val sessions: StateFlow<Map<String, CameraSession>>
    fun refreshAutoCorrectionSettings(identifier: String)
    fun setAutoCorrectionSetting(
        identifier: String,
        setting: CameraAutoCorrectionSetting,
        enabled: Boolean
    )
}
