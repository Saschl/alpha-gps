package com.sasch.cameragps.sharednew.ui.device

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceDetailRenameTest {
    @Test
    fun renamePersistsTheTrimmedNameAndShowsItImmediately() = runTest {
        val source = FakeDataSource()
        val store = DeviceDetailStateStore(source)

        store.setDeviceName("aa:bb", "  Travel camera  ")

        assertEquals("Travel camera", source.names["AA:BB"])
        assertEquals("Travel camera", store.uiState.value.deviceName)
    }

    /** A blank name would leave the row with no identity in the device list. */
    @Test
    fun blankRenameIsIgnored() = runTest {
        val source = FakeDataSource(names = mutableMapOf("AA:BB" to "ILCE-6700"))
        val store = DeviceDetailStateStore(source)
        store.load("AA:BB")

        for (blank in listOf("", "   ", "\n")) {
            store.setDeviceName("AA:BB", blank)
            assertEquals("ILCE-6700", source.names["AA:BB"])
            assertEquals("ILCE-6700", store.uiState.value.deviceName)
        }
    }

    /** Renaming a camera the platform never inserted must still create its row. */
    @Test
    fun renameEnsuresTheDeviceRowExists() = runTest {
        val source = FakeDataSource()
        DeviceDetailStateStore(source).setDeviceName("AA:BB", "Beach camera")

        assertTrue(source.ensured.contains("AA:BB"), "Rename must ensure the row exists first")
    }

    @Test
    fun loadFallsBackToThePlatformNameWhenNothingIsStored() = runTest {
        val source = FakeDataSource()
        val store = DeviceDetailStateStore(source)

        store.load("AA:BB", deviceName = "ILCE-6700")

        assertEquals("ILCE-6700", store.uiState.value.deviceName)
    }

    private class FakeDataSource(
        val names: MutableMap<String, String> = mutableMapOf(),
    ) : DeviceDetailDataSource {
        val ensured = mutableListOf<String>()

        override suspend fun ensureDeviceExists(deviceId: String, deviceName: String?) {
            ensured += deviceId
        }

        override suspend fun isDeviceEnabled(deviceId: String) = true
        override suspend fun isAlwaysOnEnabled(deviceId: String) = false
        override suspend fun isRemoteControlEnabled(deviceId: String) = false
        override suspend fun getHandshakeDelayMs(deviceId: String) = 0L
        override suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean) = Unit
        override suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean) = Unit
        override suspend fun setRemoteControlEnabled(deviceId: String, enabled: Boolean) = Unit
        override suspend fun setHandshakeDelayMs(deviceId: String, delayMs: Long) = Unit
        override suspend fun getDeviceName(deviceId: String) = names[deviceId]

        override suspend fun setDeviceName(deviceId: String, name: String) {
            names[deviceId] = name
        }
    }
}
