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
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class SonyWifiBootstrapTest {
    private class FakeBle : WifiBootstrapBlePort {
        val operations = mutableListOf<BleOperation>()
        var connected = true
        var ssidReads = 0
        var hasBssid = true
        val statuses = ArrayDeque<ByteArray>()
        var emptySsid = false
        var passwordReads = 0
        var delayPassword = false

        override fun isConnected(identifier: String) = connected
        override fun hasCharacteristic(identifier: String, characteristicUuid: String) =
            hasBssid || characteristicUuid != Sony.WIFI_BSSID_UUID

        override suspend fun execute(identifier: String, operation: BleOperation): BleOperationResult {
            operations += operation
            return when (operation) {
                is BleOperation.Read -> when (operation.characteristicUuid) {
                    Sony.CAMERA_STATUS_UUID -> BleOperationResult.Success(statuses.removeFirstOrNull() ?: byteArrayOf())
                    Sony.WIFI_SSID_UUID -> {
                        ssidReads++
                        if (emptySsid || ssidReads == 1) BleOperationResult.Failure(
                            com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus.Failure
                        ) else BleOperationResult.Success(byteArrayOf(3, 0, 0) + "DIRECT-A6700".encodeToByteArray())
                    }
                    Sony.WIFI_PASSWORD_UUID -> {
                        passwordReads++
                        BleOperationResult.Success(if (delayPassword && passwordReads == 1) byteArrayOf()
                            else byteArrayOf(3, 0, 0) + "secret-pass".encodeToByteArray())
                    }
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
            Sony.CAMERA_STATUS_UUID, Sony.CAMERA_STATUS_UUID, Sony.WIFI_ON_UUID, Sony.CAMERA_STATUS_UUID,
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

    @Test
    fun waitsForWifiStartupAndPasswordReadinessWithoutRepeatingStart() = runTest {
        val ble = FakeBle().apply {
            statuses.addAll(listOf(byteArrayOf(4, 0, 1, 0, 0), byteArrayOf(4, 0, 1, 1, 0), byteArrayOf(4, 0, 1, 2, 0)))
            delayPassword = true
        }
        assertTrue(SonyWifiBootstrap(ble).prepare("camera") != null)
        assertEquals(2, ble.passwordReads)
        assertEquals(1, ble.operations.filterIsInstance<BleOperation.Write>().size)
        val firstCredential = ble.operations.indexOfFirst { it is BleOperation.Read && it.characteristicUuid == Sony.WIFI_SSID_UUID }
        assertEquals(3, ble.operations.take(firstCredential).filterIsInstance<BleOperation.Read>().size)
    }

    @Test
    fun boundedCredentialFailureAndCancellationNeverRepeatWifiStart() = runTest {
        val ble = FakeBle().apply { emptySsid = true }
        assertNull(SonyWifiBootstrap(ble, overallTimeoutMs = 100, credentialPollMs = 10).prepare("camera"))
        assertEquals(1, ble.operations.filterIsInstance<BleOperation.Write>().size)
        ble.operations.clear()
        val job = launch { SonyWifiBootstrap(ble).prepare("camera") }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(1, ble.operations.filterIsInstance<BleOperation.Write>().size)
    }

    @Test
    fun cameraErrorStopsBeforeReadingCredentials() = runTest {
        val ble = FakeBle().apply {
            statuses.addAll(listOf(byteArrayOf(4, 0, 1, 0, 0), byteArrayOf(4, 0, 1, 0, 5)))
        }
        assertNull(SonyWifiBootstrap(ble).prepare("camera"))
        assertEquals(0, ble.ssidReads)
    }
}
