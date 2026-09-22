package com.sasch.cameragps.sharednew.remote.wifi

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Every BLE operation is delegated to the existing per-device GATT queue. */
internal interface WifiBootstrapBlePort {
    fun isConnected(identifier: String): Boolean
    fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean
    suspend fun execute(identifier: String, operation: BleOperation): BleOperationResult
}

/** Kept only for the join attempt; never put credentials in a session or diagnostic string. */
internal class CameraWifiCredentials(val ssid: String, val password: String, val bssid: String?) {
    override fun toString(): String = "CameraWifiCredentials(redacted)"
}

/**
 * APK-observed CC08 → CC06 → CC07 → optional CC0C bootstrap. CC09 provides context,
 * but an unknown status cannot block the connection indefinitely.
 */
internal class SonyWifiBootstrap(
    private val ble: WifiBootstrapBlePort,
    private val overallTimeoutMs: Long = 45_000,
    private val credentialPollMs: Long = 500,
) {
    suspend fun prepare(identifier: String): CameraWifiCredentials? = withTimeoutOrNull(overallTimeoutMs) {
        if (!ble.isConnected(identifier)) return@withTimeoutOrNull null
        if (!ble.hasCharacteristic(identifier, SonyBluetoothConstants.WIFI_ON_UUID) ||
            !ble.hasCharacteristic(identifier, SonyBluetoothConstants.WIFI_SSID_UUID) ||
            !ble.hasCharacteristic(identifier, SonyBluetoothConstants.WIFI_PASSWORD_UUID)) return@withTimeoutOrNull null

        if (ble.hasCharacteristic(identifier, SonyBluetoothConstants.CAMERA_STATUS_UUID)) {
            ble.execute(identifier, BleOperation.Subscribe(SonyBluetoothConstants.CAMERA_STATUS_UUID, true))
            ble.execute(identifier, BleOperation.Read(SonyBluetoothConstants.CAMERA_STATUS_UUID))
        }
        if (ble.execute(identifier, BleOperation.Write(
                SonyBluetoothConstants.WIFI_ON_UUID, SonyBluetoothConstants.WIFI_ON_COMMAND,
            )) !is BleOperationResult.Success) return@withTimeoutOrNull null

        var ssid: String? = null
        while (ssid == null && ble.isConnected(identifier)) {
            ssid = (ble.execute(identifier, BleOperation.Read(SonyBluetoothConstants.WIFI_SSID_UUID))
                as? BleOperationResult.Success)?.value?.let(::parsePrefixedAscii)
            if (ssid == null) delay(credentialPollMs)
        }
        if (ssid == null) return@withTimeoutOrNull null

        val password = (ble.execute(identifier, BleOperation.Read(SonyBluetoothConstants.WIFI_PASSWORD_UUID))
            as? BleOperationResult.Success)?.value?.let(::parsePrefixedAscii) ?: return@withTimeoutOrNull null
        val bssid = if (ble.hasCharacteristic(identifier, SonyBluetoothConstants.WIFI_BSSID_UUID)) {
            (ble.execute(identifier, BleOperation.Read(SonyBluetoothConstants.WIFI_BSSID_UUID))
                as? BleOperationResult.Success)?.value?.let(::parseAscii)
        } else null
        CameraWifiCredentials(ssid, password, bssid)
    }

    private fun parsePrefixedAscii(value: ByteArray): String? =
        if (value.size > 3) parseAscii(value.copyOfRange(3, value.size)) else null

    private fun parseAscii(value: ByteArray): String? {
        if (value.isEmpty() || value.any { it.toInt() !in 32..126 }) return null
        return value.map { it.toInt().toChar() }.joinToString("")
    }
}
