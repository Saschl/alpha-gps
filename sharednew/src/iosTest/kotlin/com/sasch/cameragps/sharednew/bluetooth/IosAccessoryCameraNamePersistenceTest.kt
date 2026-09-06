package com.sasch.cameragps.sharednew.bluetooth

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IosAccessoryCameraNamePersistenceTest {
    @Test
    fun newCameraStoresHardwareNameInsteadOfPickerPlaceholder() = runTest {
        withRepository { dao, repository ->
            repository.ensureDeviceRecord(ID.lowercase(), "Camera", "ILCE-6700")
            assertEquals("ILCE-6700", dao.getAllCameraDevices().single().deviceName)
            repository.sync()
            assertEquals("ILCE-6700", repository.deviceNameFor(ID))
        }
    }

    @Test
    fun existingPlaceholderIsUpdatedWithoutResettingSettings() = runTest {
        withRepository { dao, repository ->
            dao.insertDevice(SAVED)
            // No prior sync: name resolution must read the real database row.
            repository.ensureDeviceRecord(ID.lowercase(), "Camera", "ILCE-6700")
            assertEquals(SAVED.copy(deviceName = "ILCE-6700"), dao.getAllCameraDevices().single())
            repository.sync()
            assertEquals("ILCE-6700", repository.deviceNameFor(ID))
            repository.ensureDeviceRecord(ID, "Camera", null)
            assertEquals("ILCE-6700", dao.getAllCameraDevices().single().deviceName)
        }
    }

    @Test
    fun savedCustomNameSurvivesGenericSystemMetadataWithAnEmptyCache() = runTest {
        withRepository { dao, repository ->
            dao.insertDevice(SAVED.copy(deviceName = "Travel camera"))
            repository.ensureDeviceRecord(ID, "Camera", "ILCE-6700")
            assertEquals(SAVED.copy(deviceName = "Travel camera"), dao.getAllCameraDevices().single())
        }
    }

    @Test
    fun systemRenameIsPersistedWithoutReplacingTheDeviceRow() = runTest {
        withRepository { dao, repository ->
            dao.insertDevice(SAVED.copy(deviceName = "ILCE-6700"))
            repository.ensureDeviceRecord(ID, "Travel camera", "ILCE-6700")
            assertEquals(SAVED.copy(deviceName = "Travel camera"), dao.getAllCameraDevices().single())
        }
    }

    private suspend fun withRepository(block: suspend (CameraDeviceDAO, IosDeviceRepository) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder<LogDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
        try {
            val dao = database.cameraDeviceDao()
            block(dao, IosDeviceRepository(deviceDao = { dao }, resolveNames = { emptyMap() }))
        } finally {
            database.close()
        }
    }

    private companion object {
        const val ID = "AAAAAAAA-0000-0000-0000-000000000001"
        val SAVED = CameraDevice(
            mac = ID,
            deviceName = "Camera",
            deviceEnabled = false,
            alwaysOnEnabled = true,
            remoteControlEnabled = true,
            handshakeDelayMs = 2_000,
        )
    }
}
