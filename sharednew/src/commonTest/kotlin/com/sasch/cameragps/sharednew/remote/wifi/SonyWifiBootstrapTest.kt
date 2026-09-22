package com.sasch.cameragps.sharednew.remote.wifi

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants as Sony
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SonyWifiBootstrapTest {
    private class FakeBle : WifiBootstrapBlePort {
        val operations = mutableListOf<BleOperation>()
        var connected = true
        var ssidReads = 0
        var hasBssid = true

        override fun isConnected(identifier: String) = connected
        override fun hasCharacteristic(identifier: String, characteristicUuid: String) =
            hasBssid || characteristicUuid != Sony.WIFI_BSSID_UUID

        override suspend fun execute(identifier: String, operation: BleOperation): BleOperationResult {
            operations += operation
            return when (operation) {
                is BleOperation.Read -> when (operation.characteristicUuid) {
                    Sony.CAMERA_STATUS_UUID -> BleOperationResult.Success(byteArrayOf())
                    Sony.WIFI_SSID_UUID -> {
                        ssidReads++
                        if (ssidReads == 1) BleOperationResult.Failure(
                            com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus.Failure
                        ) else BleOperationResult.Success(byteArrayOf(3, 0, 0) + "DIRECT-A6700".encodeToByteArray())
                    }
                    Sony.WIFI_PASSWORD_UUID -> BleOperationResult.Success(byteArrayOf(3, 0, 0) + "secret-pass".encodeToByteArray())
                    Sony.WIFI_BSSID_UUID -> BleOperationResult.Success("01:02:03:04:05:06".encodeToByteArray())
                    else -> error("Unexpected read")
                }
                else -> BleOperationResult.Success()
            }
        }
    }

    @Test
    fun bootsThroughGattQueueAndKeepsCredentialsOutOfDiagnostics() = runTest {
        val ble = FakeBle()
        val credentials = SonyWifiBootstrap(ble, credentialPollMs = 10).prepare("camera")!!
        assertEquals("DIRECT-A6700", credentials.ssid)
        assertEquals("secret-pass", credentials.password)
        assertEquals("01:02:03:04:05:06", credentials.bssid)
        assertFalse(credentials.toString().contains(credentials.password))
        assertEquals(listOf(
            Sony.CAMERA_STATUS_UUID, Sony.CAMERA_STATUS_UUID, Sony.WIFI_ON_UUID,
            Sony.WIFI_SSID_UUID, Sony.WIFI_SSID_UUID, Sony.WIFI_PASSWORD_UUID, Sony.WIFI_BSSID_UUID,
        ), ble.operations.map { when (it) {
            is BleOperation.Read -> it.characteristicUuid
            is BleOperation.Write -> it.characteristicUuid
            is BleOperation.Subscribe -> it.characteristicUuid
            BleOperation.DiscoverServices -> "discover"
        } })
        assertContentEquals(Sony.WIFI_ON_COMMAND, (ble.operations[2] as BleOperation.Write).value)
    }

    @Test
    fun missingOptionalBssidDoesNotFail() = runTest {
        val ble = FakeBle().apply { hasBssid = false; ssidReads = 1 }
        val credentials = SonyWifiBootstrap(ble).prepare("camera")!!
        assertNull(credentials.bssid)
    }

    @Test
    fun disconnectedCameraCannotStartBootstrap() = runTest {
        val ble = FakeBle().apply { connected = false }
        assertNull(SonyWifiBootstrap(ble).prepare("camera"))
        assertEquals(emptyList(), ble.operations)
    }
}
