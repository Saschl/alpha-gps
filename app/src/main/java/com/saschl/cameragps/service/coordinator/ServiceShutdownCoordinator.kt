package com.saschl.cameragps.service.coordinator

import android.annotation.SuppressLint
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.saschl.cameragps.service.CameraConnectionManager
import com.saschl.cameragps.service.ServiceEvent
import com.saschl.cameragps.service.ServiceEventBus
import timber.log.Timber

class ServiceShutdownCoordinator(
    private val deviceDao: CameraDeviceDAO,
    private val cameraConnectionManager: CameraConnectionManager,
    private val eventBus: ServiceEventBus,
) {
    @SuppressLint("MissingPermission")
    suspend fun handleNoAddress(startId: Int) {
        if (deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.i("No always-on devices found, shutting down service")
            eventBus.emit(ServiceEvent.RequestShutdown(startId))
            return
        }

        runCatching {
            deviceDao.getAllCameraDevices()
                .filter { it.alwaysOnEnabled }
                .forEach { device ->
                    runCatching {
                        cameraConnectionManager.connect(device.mac)
                    }.onFailure { handleGattConnectionFailure(startId, device) }
                }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleGattConnectionFailure(startId: Int, cameraDevice: CameraDevice) {
        Timber.e("Failed to connect to device ${cameraDevice.deviceName}, bluetooth is likely disabled")
    }

    @SuppressLint("MissingPermission")
    suspend fun handleShutdownRequest(address: String, startId: Int) {
        Timber.i("Shutdown requested for device $address")

        if (address == "all") {
            handleShutdownAllDevices(startId)
            return
        }

        // Sometimes false "disappeared" events appear, so keep the device active if it was always on.
        if (!deviceDao.isDeviceAlwaysOnEnabled(address)) {
            cameraConnectionManager.pauseDevice(address)
        }
        if (cameraConnectionManager.getActiveCameras()
                .isEmpty() && deviceDao.getAlwaysOnEnabledDeviceCount() == 0
        ) {
            Timber.d("No connected or always on cameras remaining, shutting down service")
            eventBus.emit(ServiceEvent.RequestShutdown(startId))
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleShutdownAllDevices(startId: Int) {
        if (deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.i("No always-on devices found, disconnecting all cameras and shutting down service")
            cameraConnectionManager.disconnectAll()
            eventBus.emit(ServiceEvent.RequestShutdown(startId))
        } else {
            Timber.i("At least one always-on device found, not shutting down service")
        }
    }
}
