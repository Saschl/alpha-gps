package com.saschl.cameragps.service.coordinator

import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionEvent
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.saschl.cameragps.service.CameraConnectionManager
import com.saschl.cameragps.service.ServiceEvent
import com.saschl.cameragps.service.ServiceEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteControlCoordinator as SharedRemoteControlCoordinator

/**
 * Android-specific thin adapter over the shared [SharedRemoteControlCoordinator].
 *
 * Translates Android BluetoothGatt callbacks into the shared coordinator's
 * platform-agnostic API, and bridges shared [BleSessionEvent]s to Android [ServiceEvent]s.
 */
class RemoteControlCoordinator(
    cameraConnectionManager: CameraConnectionManager,
    private val eventBus: ServiceEventBus,
    scope: CoroutineScope,
    deviceDAO: CameraDeviceDAO
) {
    internal val port = AndroidBleGattPort(cameraConnectionManager)
    internal val shared = SharedRemoteControlCoordinator(port, scope)

    init {
        // Bridge shared events → Android ServiceEventBus
        scope.launch {
            shared.events.collect { event ->
                when (event) {
                    is BleSessionEvent.RemoteFeatureActivated ->
                        eventBus.emit(ServiceEvent.RemoteFeatureActivated(event.identifier))

                    is BleSessionEvent.RemoteFeatureDeactivated ->
                        eventBus.emit(ServiceEvent.RemoteFeatureDeactivated(event.identifier))

                    // Session events are handled by BleSessionCoordinator's own event bridge
                    is BleSessionEvent.PhaseChanged -> {

                    }
                    is BleSessionEvent.HandshakeComplete -> {
                        if (deviceDAO.isRemoteControlEnabled(event.identifier)) {
                            shared.startRemoteStatusMonitoring(event.identifier)
                        }
                    }
                }
            }
        }
    }

    /*   @SuppressLint("MissingPermission")
       fun handleRemoteShutterRequest(address: String): Boolean {
           val success = shared.handleRemoteShutterRequest(address)
           if (!success) {
               Timber.w("Remote shutter request failed for ${address.uppercase()}")
           }
           return success
       }*/

    /* @SuppressLint("MissingPermission")
     fun triggerRemoteStatusProbe(gatt: BluetoothGatt) {
         val address = gatt.device.address.uppercase()
         shared.startRemoteStatusMonitoring(address)
     }

     fun handleRemoteStatusCharacteristicChanged(
         gatt: BluetoothGatt,
         value: ByteArray,
     ): Boolean {
         val address = gatt.device.address.uppercase()
         return shared.onRemoteStatusChanged(address, value)
     }

     @SuppressLint("MissingPermission")
     fun writeRemoteShutterUpIfSupported(gatt: BluetoothGatt): Boolean {
         return shared.sendShutterUp(gatt.device.address.uppercase())
     }

     fun handleRemoteStatusCharacteristicWriteResponse(
         gatt: BluetoothGatt,
         success: Boolean,
     ) {
         shared.onRemoteControlWriteResponse(gatt.device.address.uppercase(), success)
     }*/

    fun cancelRemoteStatusProbe(address: String) {
        shared.cancelProbe(address)
    }

    fun setRemoteStatusMonitoringEnabled(address: String, enabled: Boolean) {
        val normalized = address.uppercase()
        if (enabled) {
            shared.startRemoteStatusMonitoring(normalized)
        } else {
            shared.cancelProbe(normalized)
        }
    }

    fun cancelAllProbes() {
        shared.cancelAllProbes()
    }
}
