package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.IosAppPreferences
import com.sasch.cameragps.sharednew.IosMigrationReminder
import com.sasch.cameragps.sharednew.database.devices.CameraDevice

/** Inert adapter over the existing repository, migration preference and reminder service. */
internal class IosAccessoryMigrationStore(
    private val repository: IosDeviceRepository,
) : IosAccessoryCoordinator.Store {
    override var migrationDone: Boolean
        get() = IosAppPreferences.isAccessoryMigrationDone()
        set(value) = IosAppPreferences.setAccessoryMigrationDone(value)

    override val savedDevices: Collection<CameraDevice> get() = repository.savedDevices.values

    override suspend fun loadSavedDevices() {
        repository.loadStoreFromDisk()
        repository.migrateLegacyDevicesToDatabase()
        repository.sync()
    }

    override suspend fun sync() = repository.sync()

    override suspend fun remindMigrationPending(requestPermission: Boolean) {
        if (requestPermission) IosMigrationReminder.requestAuthorizationIfForeground()
        IosMigrationReminder.armMigrationPending()
    }

    override fun cancelReminder() = IosMigrationReminder.cancel()
}
