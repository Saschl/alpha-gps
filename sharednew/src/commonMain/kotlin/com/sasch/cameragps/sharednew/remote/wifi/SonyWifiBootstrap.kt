package com.sasch.cameragps.sharednew.remote.wifi

import com.diamondedge.logging.logging
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

/** Held only while joining; diagnostic strings must never expose credentials. */
class CameraWifiCredentials(val ssid: String, val password: String, val bssid: String?) {
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
    private val log = logging()

    suspend fun prepare(identifier: String): CameraWifiCredentials? {
        var startAccepted = false
        var stage = "activation"
        val credentials = withTimeoutOrNull(overallTimeoutMs) {
            if (!ble.isConnected(identifier)) return@withTimeoutOrNull null
            if (!ble.hasCharacteristic(identifier, SonyBluetoothConstants.WIFI_ON_UUID) ||
                !ble.hasCharacteristic(identifier, SonyBluetoothConstants.WIFI_SSID_UUID) ||
                !ble.hasCharacteristic(identifier, SonyBluetoothConstants.WIFI_PASSWORD_UUID)
            ) return@withTimeoutOrNull null

            if (ble.hasCharacteristic(identifier, SonyBluetoothConstants.CAMERA_STATUS_UUID)) {
                ble.execute(
                    identifier,
                    BleOperation.Subscribe(SonyBluetoothConstants.CAMERA_STATUS_UUID, true)
                )
                ble.execute(
                    identifier,
                    BleOperation.Read(SonyBluetoothConstants.CAMERA_STATUS_UUID)
                )
            }
            if (ble.execute(
                    identifier, BleOperation.Write(
                        SonyBluetoothConstants.WIFI_ON_UUID, SonyBluetoothConstants.WIFI_ON_COMMAND,
                    )
                ) !is BleOperationResult.Success
            ) return@withTimeoutOrNull null
            startAccepted = true
            stage = "camera status"
            log.i { "Camera accepted Wi-Fi start command; waiting for connection details" }

            if (ble.hasCharacteristic(identifier, SonyBluetoothConstants.CAMERA_STATUS_UUID)) {
                while (ble.isConnected(identifier)) {
                    val status = (ble.execute(
                        identifier,
                        BleOperation.Read(SonyBluetoothConstants.CAMERA_STATUS_UUID)
                    )
                            as? BleOperationResult.Success)?.value?.let(::parseWifiStatus) ?: break
                    if (status.first == 2) break
                    if (status.first == 0 && status.second in 2..5) {
                        startAccepted = false
                        log.w { "Camera reported Wi-Fi startup error=${status.second}" }
                        return@withTimeoutOrNull null
                    }
                    delay(credentialPollMs)
                }
            }

            stage = "SSID"
            var ssid: String? = null
            while (ssid == null && ble.isConnected(identifier)) {
                ssid = (ble.execute(
                    identifier,
                    BleOperation.Read(SonyBluetoothConstants.WIFI_SSID_UUID)
                )
                        as? BleOperationResult.Success)?.value?.let(::parsePrefixedAscii)
                if (ssid == null) delay(credentialPollMs)
            }
            if (ssid == null) return@withTimeoutOrNull null

            stage = "password"
            var password: String? = null
            while (password == null && ble.isConnected(identifier)) {
                password = (ble.execute(
                    identifier,
                    BleOperation.Read(SonyBluetoothConstants.WIFI_PASSWORD_UUID)
                )
                        as? BleOperationResult.Success)?.value?.let(::parsePrefixedAscii)
                if (password == null) delay(credentialPollMs)
            }
            if (password == null || ssid.length !in 1..32 || password.length !in 8..63) return@withTimeoutOrNull null
            stage = "BSSID"
            val bssid =
                if (ble.hasCharacteristic(identifier, SonyBluetoothConstants.WIFI_BSSID_UUID)) {
                    (ble.execute(
                        identifier,
                        BleOperation.Read(SonyBluetoothConstants.WIFI_BSSID_UUID)
                    )
                            as? BleOperationResult.Success)?.value?.let(::parseAscii)
                } else null
            CameraWifiCredentials(ssid, password, bssid)
        }
        if (credentials == null) {
            log.w { "Camera Wi-Fi setup failed during $stage; startAccepted=$startAccepted" }
            if (startAccepted) throw WifiRemoteConnectException(WifiRemoteFailure.CameraCredentialsUnavailable)
        } else {
            log.i { "Camera Wi-Fi connection details read successfully" }
        }
        return credentials
    }

    private fun parsePrefixedAscii(value: ByteArray): String? =
        if (value.size > 3) parseAscii(value.copyOfRange(3, value.size)) else null

    private fun parseAscii(value: ByteArray): String? {
        if (value.isEmpty() || value.any { it.toInt() !in 32..126 }) return null
        return value.map { it.toInt().toChar() }.joinToString("")
    }

    /** CC09 type 1: Wi-Fi state (0 off, 1 starting, 2 on, 3 stopping), error. */
    private fun parseWifiStatus(value: ByteArray): Pair<Int, Int>? {
        var offset = 0
        while (offset < value.size) {
            val length = value[offset].toInt() and 0xff
            if (length < 2 || offset + length + 1 > value.size) return null
            val type = ((value[offset + 1].toInt() and 0xff) shl 8) or (value[offset + 2].toInt() and 0xff)
            if (type == 1 && length >= 4) {
                val state = value[offset + 3].toInt() and 0xff
                return if (state in 0..3) state to (value[offset + 4].toInt() and 0xff) else null
            }
            offset += length + 1
        }
        return null
    }
}
