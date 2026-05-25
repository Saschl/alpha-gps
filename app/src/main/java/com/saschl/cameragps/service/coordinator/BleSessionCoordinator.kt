package com.saschl.cameragps.service.coordinator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import androidx.annotation.RequiresPermission
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionEvent
import com.saschl.cameragps.service.CameraConnectionManager
import com.saschl.cameragps.service.ServiceEvent
import com.saschl.cameragps.service.ServiceEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionCoordinator as SharedBleSessionCoordinator

/**
 * Android-specific thin adapter over the shared [SharedBleSessionCoordinator].
 *
 * Translates Android BluetoothGatt callbacks into the shared coordinator's
 * platform-agnostic API, and bridges shared [BleSessionEvent]s to Android [ServiceEvent]s.
 */
class BleSessionCoordinator(
    private val cameraConnectionManager: CameraConnectionManager,
    private val remoteControlCoordinator: RemoteControlCoordinator,
    private val eventBus: ServiceEventBus,
    scope: CoroutineScope,
) {
    private val shared = SharedBleSessionCoordinator(
        remoteControlCoordinator.port,
        remoteControlCoordinator.shared,
    )

    private var hasRetriedRead = false;

    init {
        // Bridge shared session events → Android ServiceEventBus
        scope.launch {
            shared.events.collect { event ->
                when (event) {
                    is BleSessionEvent.PhaseChanged ->
                        eventBus.emit(
                            ServiceEvent.PhaseChanged(
                                event.identifier,
                                event.phase,
                                event.remoteActive
                            )
                        )

                    is BleSessionEvent.HandshakeComplete ->
                        eventBus.emit(ServiceEvent.HandshakeComplete(event.identifier))

                    // Remote events are handled by RemoteControlCoordinator's own event bridge
                    is BleSessionEvent.RemoteFeatureActivated -> {}
                    is BleSessionEvent.RemoteFeatureDeactivated -> {}
                }
            }
        }
    }

    fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Timber.w("Service discovery failed for ${gatt.device.address} with status=$status")
            eventBus.emit(ServiceEvent.PhaseChanged(gatt.device.address, BleSessionPhase.Error))
            return
        }

        // Store Android-specific characteristic references in connection manager
        // (needed by AndroidBleGattPort for remote-control operations)
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

        // Delegate handshake to shared coordinator
        shared.beginHandshake(address)
    }

    fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        Timber.i("Characteristic changed: ${characteristic.uuid}, value=${value.joinToString(",")}")
        shared.onCharacteristicChanged(gatt.device.address, characteristic.uuid.toString(), value)
    }

    @SuppressLint("MissingPermission")
    fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        writtenCharacteristic: BluetoothGattCharacteristic?,
        status: Int,
    ) {
        val uuid = writtenCharacteristic?.uuid?.toString() ?: return
        shared.onCharacteristicWrite(
            gatt.device.address,
            uuid,
            status == BluetoothGatt.GATT_SUCCESS,
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun onCharacteristicRead(gatt: BluetoothGatt, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            // Retry once if read failed, as we sometimes face a 133 error on the first read
            if (!hasRetriedRead) {
                Timber.w("Characteristic read failed for ${gatt.device.address} with status=$status. Will retry")
                hasRetriedRead = true
                gatt.readCharacteristic(
                    gatt.services
                        ?.flatMap { it.characteristics }
                        ?.find { it.uuid == constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_READ_UUID) }
                )
            } else {
                Timber.e("Characteristic read failed for ${gatt.device.address} with status=$status. Will retry")
                eventBus.emit(ServiceEvent.PhaseChanged(gatt.device.address, BleSessionPhase.Error))

            }
            return
        }

        shared.onCharacteristicRead(
            gatt.device.address,
            value,
            status == BluetoothGatt.GATT_SUCCESS,
        )

        // Also update Android connection manager for LocationTransmissionCoordinator
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val config = shared.getLocationDataConfig(gatt.device.address.uppercase())
            if (config != null) {
                cameraConnectionManager.setLocationDataConfig(
                    gatt.device.address.uppercase(),
                    config,
                )
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

    fun triggerRemoteShutter(address: String): Boolean {
        return shared.triggerRemoteShutter(address)
    }

    private fun constructBleUUID(characteristic: String): UUID = UUID.fromString(characteristic)
}
