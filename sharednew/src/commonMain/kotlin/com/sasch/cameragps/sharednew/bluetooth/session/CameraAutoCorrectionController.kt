package com.sasch.cameragps.sharednew.bluetooth.session

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Optional camera settings, independent of the sequence-sensitive GPS handshake. */
internal class CameraAutoCorrectionController(
    private val port: QueuedBleGattPort,
    private val registry: CameraSessionRegistry,
    private val scope: CoroutineScope,
) {
    private val jobs = mutableMapOf<Pair<String, CameraAutoCorrectionSetting>, Job>()

    fun refresh(identifier: String) {
        val id = identifier.uppercase()
        if (!isReady(id)) return
        for (setting in CameraAutoCorrectionSetting.entries) {
            val old = registry.get(id)?.autoCorrectionSetting(setting) ?: continue
            if (old.pending) continue
            if (!port.hasCharacteristic(id, setting.characteristicUuid)) {
                port.setAutoCorrectionState(id, setting, CameraSettingState(supported = false))
                continue
            }
            port.setAutoCorrectionState(
                id,
                setting,
                old.copy(supported = true, pending = true, failed = false)
            )
            jobs[id to setting] = scope.launch {
                val result = port.execute(id, BleOperation.Read(setting.characteristicUuid))
                coroutineContext.ensureActive()
                val bytes = (result as? BleOperationResult.Success)?.value
                val value = if (bytes?.size == 1) when (bytes[0].toInt()) {
                    0 -> false
                    1 -> true
                    else -> null
                } else null
                port.setAutoCorrectionState(
                    id, setting, CameraSettingState(
                        supported = true, enabled = value, failed = value == null,
                    )
                )
            }
        }
    }

    fun set(identifier: String, setting: CameraAutoCorrectionSetting, enabled: Boolean) {
        val id = identifier.uppercase()
        if (!isReady(id)) return
        val old = registry.get(id)?.autoCorrectionSetting(setting) ?: return
        if (old.supported != true || old.enabled == null || old.pending || old.enabled == enabled) return
        port.setAutoCorrectionState(id, setting, old.copy(pending = true, failed = false))
        jobs[id to setting] = scope.launch {
            val result = port.execute(
                id, BleOperation.Write(
                    setting.characteristicUuid, byteArrayOf(if (enabled) 1 else 0),
                )
            )
            coroutineContext.ensureActive()
            val success = result is BleOperationResult.Success
            port.setAutoCorrectionState(
                id, setting, old.copy(
                    enabled = if (success) enabled else old.enabled,
                    pending = false, failed = !success,
                )
            )
        }
    }

    fun clear(identifier: String) {
        val keys = jobs.keys.filter { it.first == identifier.uppercase() }
        keys.forEach { jobs.remove(it)?.cancel() }
    }

    fun clearAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun isReady(id: String) =
        port.isConnected(id) && registry.get(id)?.phase == BleSessionPhase.Transmitting
}
