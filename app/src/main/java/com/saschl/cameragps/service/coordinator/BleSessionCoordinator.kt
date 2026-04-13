package com.saschl.cameragps.service.coordinator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.CHARACTERISTIC_READ_UUID
import com.saschl.cameragps.service.BluetoothGattUtils
import com.saschl.cameragps.service.CameraConnectionManager
import com.saschl.cameragps.service.LocationDataConfig
import com.saschl.cameragps.service.LocationDataConverter
import com.saschl.cameragps.service.ServiceEvent
import com.saschl.cameragps.service.ServiceEventBus
import timber.log.Timber
import java.time.ZonedDateTime
import java.util.UUID

class BleSessionCoordinator(
    private val cameraConnectionManager: CameraConnectionManager,
    private val remoteControlCoordinator: RemoteControlCoordinator,
    private val eventBus: ServiceEventBus,
) {

    fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Timber.w("Service discovery failed for ${gatt.device.address} with status=$status")
            eventBus.emit(ServiceEvent.PhaseChanged(gatt.device.address, BleSessionPhase.Error))
            return
        }

        eventBus.emit(
            ServiceEvent.PhaseChanged(
                gatt.device.address,
                BleSessionPhase.DiscoveringServices
            )
        )

        val service =
            gatt.services?.find { it.uuid == constructBleUUID(SonyBluetoothConstants.SERVICE_UUID) }
        val remoteService =
            gatt.services?.find { it.uuid == constructBleUUID(SonyBluetoothConstants.REMOTE_SERVICE_UUID) }

        val writeLocationCharacteristic =
            service?.getCharacteristic(constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_UUID))
        val remoteControlCharacteristic =
            remoteService?.getCharacteristic(constructBleUUID(SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID))
        val remoteStatusCharacteristic =
            remoteService?.getCharacteristic(constructBleUUID(SonyBluetoothConstants.REMOTE_STATUS_UUID))
        val remoteStatusDescriptor =
            remoteStatusCharacteristic?.getDescriptor(constructBleUUID(SonyBluetoothConstants.CCCD_UUID))

        val address = gatt.device.address.uppercase()
        cameraConnectionManager.setWriteCharacteristic(address, writeLocationCharacteristic)
        cameraConnectionManager.setRemoteControlCharacteristic(address, remoteControlCharacteristic)
        cameraConnectionManager.setRemoteStatusCharacteristic(address, remoteStatusCharacteristic)
        cameraConnectionManager.setRemoteStatusDescriptor(address, remoteStatusDescriptor)
        cameraConnectionManager.setRemoteFeatureActive(address, false)
        eventBus.emit(
            ServiceEvent.PhaseChanged(
                gatt.device.address,
                BleSessionPhase.DiscoveringServices,
                false
            )
        )

        handleServicesDiscovered(gatt, service)
    }

    fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        Timber.i("Characteristic changed: ${characteristic.uuid}, value=${value.joinToString(",")}")

        if (characteristic.uuid == constructBleUUID(SonyBluetoothConstants.REMOTE_STATUS_UUID)) {
            onRemoteStatusCharacteristicChanged(gatt, value)
        }

        if (characteristic.uuid.toString().uppercase().startsWith("0000DD01")) {
            Timber.w("Received characteristic change from camera: ${characteristic.uuid}, $value")
        } else {
            Timber.i("Received characteristic change from camera: ${characteristic.uuid}, $value")
        }
    }

    @SuppressLint("MissingPermission")
    fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        writtenCharacteristic: BluetoothGattCharacteristic?,
        status: Int,
    ) {
        /*  if (status != BluetoothGatt.GATT_SUCCESS) {
              Timber.w("Characteristic write failed for ${writtenCharacteristic?.uuid} with status=$status")
              eventBus.emit(ServiceEvent.PhaseChanged(gatt.device.address, BleSessionPhase.Error))
              return
          }*/

        when (writtenCharacteristic?.uuid) {
            constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND) -> {
                handleGpsEnableResponse(gatt)
            }

            constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND) -> {
                Timber.i("GPS flag enabled on device, will now send time sync data if feature exists, status was $status")
                sendTimeSyncData(gatt)
            }

            constructBleUUID(SonyBluetoothConstants.TIME_SYNC_CHARACTERISTIC_UUID) -> {
                Timber.i("Time sync data sent to device, will now start location transmission, status was $status")
                startLocationTransmissionAndProbeRemote(gatt)
            }

            constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_UUID) -> {
                Timber.d("Location data sent to device, status was $status")
            }

            constructBleUUID(SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID) -> {
                remoteControlCoordinator.handleRemoteStatusCharacteristicWriteResponse(
                    gatt,
                    status == BluetoothGatt.GATT_SUCCESS
                )
            }

            else -> {
                Timber.w("Unknown characteristic written: ${writtenCharacteristic?.uuid}, status was $status")
            }
        }
    }

    fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (descriptor.uuid == constructBleUUID(SonyBluetoothConstants.CCCD_UUID)) {
            Timber.d("Descriptor write completed for ${gatt.device.address} with status $status")
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun onCharacteristicRead(gatt: BluetoothGatt, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Timber.w("Characteristic read failed for ${gatt.device.address} with status=$status")
            eventBus.emit(ServiceEvent.PhaseChanged(gatt.device.address, BleSessionPhase.Error))
            return
        }

        cameraConnectionManager.setLocationDataConfig(
            gatt.device.address.uppercase(),
            LocationDataConfig(hasTimeZoneDstFlag(value))
        )

        Timber.i("Characteristic read, shouldSendTimeZoneAndDst: ${hasTimeZoneDstFlag(value)}")
        enableGpsTransmission(gatt)
    }

    fun onRemoteStatusCharacteristicChanged(gatt: BluetoothGatt, value: ByteArray) {
        val shouldSendShutterUp =
            remoteControlCoordinator.handleRemoteStatusCharacteristicChanged(gatt, value)
        if (shouldSendShutterUp) {
            val wroteUp = remoteControlCoordinator.writeRemoteShutterUpIfSupported(gatt)
            if (!wroteUp) {
                Timber.w("Remote shutter up command failed")
            }
        }
    }

    fun handleRemoteShutterRequest(address: String): Boolean {
        return remoteControlCoordinator.handleRemoteShutterRequest(address)
    }

    // --- private helpers ---

    @SuppressLint("MissingPermission")
    private fun startLocationTransmissionAndProbeRemote(gatt: BluetoothGatt) {
        eventBus.emit(ServiceEvent.HandshakeComplete(gatt.device.address))
        eventBus.emit(
            ServiceEvent.PhaseChanged(
                gatt.device.address,
                BleSessionPhase.Transmitting,
                null
            )
        )

        remoteControlCoordinator.triggerRemoteStatusProbe(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun handleServicesDiscovered(gatt: BluetoothGatt, service: BluetoothGattService?) {
        val readCharacteristic =
            service?.getCharacteristic(constructBleUUID(CHARACTERISTIC_READ_UUID))
        if (readCharacteristic != null) {
            eventBus.emit(
                ServiceEvent.PhaseChanged(
                    gatt.device.address,
                    BleSessionPhase.ReadingConfig
                )
            )
            Timber.i("Reading characteristic for timezone and DST support: ${readCharacteristic.uuid}")
            gatt.readCharacteristic(readCharacteristic)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun enableGpsTransmission(gatt: BluetoothGatt) {
        val service =
            gatt.services?.find { it.uuid == constructBleUUID(SonyBluetoothConstants.SERVICE_UUID) }
        val gpsEnableCharacteristic =
            service?.getCharacteristic(constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND))

        if (gpsEnableCharacteristic != null) {
            eventBus.emit(
                ServiceEvent.PhaseChanged(
                    gatt.device.address,
                    BleSessionPhase.EnablingGps
                )
            )
            Timber.i("Enabling GPS characteristic: ${gpsEnableCharacteristic.uuid}")
            BluetoothGattUtils.writeCharacteristic(
                gatt,
                gpsEnableCharacteristic,
                SonyBluetoothConstants.GPS_ENABLE_COMMAND
            )
        } else {
            Timber.i("Characteristic to enable GPS does not exist, starting transmission directly")
            startLocationTransmissionAndProbeRemote(gatt)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun sendTimeSyncData(gatt: BluetoothGatt) {
        val service =
            gatt.services?.find { it.uuid == constructBleUUID(SonyBluetoothConstants.CONTROL_SERVICE_UUID) }
        val timeSyncCharacteristic =
            service?.getCharacteristic(constructBleUUID(SonyBluetoothConstants.TIME_SYNC_CHARACTERISTIC_UUID))

        if (timeSyncCharacteristic == null) {
            Timber.i("Time sync characteristic not found, starting location transmission directly")
            startLocationTransmissionAndProbeRemote(gatt)
            return
        }

        eventBus.emit(ServiceEvent.PhaseChanged(gatt.device.address, BleSessionPhase.SyncingTime))
        val timeSyncPacket = LocationDataConverter.serializeTimeAreaData(ZonedDateTime.now())
        Timber.d("Sending time sync data to camera")

        if (!BluetoothGattUtils.writeCharacteristic(gatt, timeSyncCharacteristic, timeSyncPacket)) {
            Timber.e("Failed to send time sync data to camera, starting location transmission directly")
            startLocationTransmissionAndProbeRemote(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleGpsEnableResponse(gatt: BluetoothGatt) {
        val lockCharacteristic = BluetoothGattUtils.findCharacteristic(
            gatt,
            constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND)
        )

        lockCharacteristic?.let {
            eventBus.emit(
                ServiceEvent.PhaseChanged(
                    gatt.device.address,
                    BleSessionPhase.LockingGps
                )
            )
            Timber.i("Found characteristic to lock GPS: ${it.uuid}")
            BluetoothGattUtils.writeCharacteristic(
                gatt,
                it,
                SonyBluetoothConstants.GPS_ENABLE_COMMAND
            )
        }
    }

    private fun hasTimeZoneDstFlag(value: ByteArray): Boolean {
        return value.size >= 5 && (value[4].toInt() and 0x02) != 0
    }

    private fun constructBleUUID(characteristic: String): UUID = UUID.fromString(characteristic)
}
