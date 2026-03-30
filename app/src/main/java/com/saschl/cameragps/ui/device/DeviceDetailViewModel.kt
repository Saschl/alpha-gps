package com.saschl.cameragps.ui.device

import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.saschl.cameragps.service.AssociatedDeviceCompat
import com.saschl.cameragps.service.LocationSenderService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds


data class ServiceToggleState(
    val buttonEnabled: Boolean = true,
    val isDeviceEnabled: Boolean = true,
    val isAlwaysOnEnabled: Boolean = false,
)

class DeviceDetailViewModel(private val cameraDeviceDAO: CameraDeviceDAO) : ViewModel() {

    companion object {

        val MY_REPOSITORY_KEY = object : CreationExtras.Key<CameraDeviceDAO> {}
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val myRepository = this[MY_REPOSITORY_KEY] as CameraDeviceDAO
                DeviceDetailViewModel(
                    cameraDeviceDAO = myRepository,
                )
            }
        }
    }

    // Expose screen UI state
    private val _uiState = MutableStateFlow(ServiceToggleState())
    val uiState: StateFlow<ServiceToggleState> = _uiState.asStateFlow()


    fun setDeviceEnabled(isEnabled: Boolean, device: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeviceEnabled = isEnabled) }
            cameraDeviceDAO.setDeviceEnabled(device, isEnabled)
        }
    }

    suspend fun stopServiceWithDelay(
        context: Context,
        device: AssociatedDeviceCompat,
        deviceManager: CompanionDeviceManager
    ) {

        Timber.i("Stopping LocationSenderService from detail for device ${device.address}")
        _uiState.update { it.copy(buttonEnabled = false) }
        val shutdownIntent = Intent(context, LocationSenderService::class.java).apply {
            action = SonyBluetoothConstants.ACTION_REQUEST_SHUTDOWN

        }
        shutdownIntent.putExtra("address", device.address.uppercase())

        context.startService(shutdownIntent)
        delay(2.seconds)
        //startDevicePresenceObservation(deviceManager, device)
        _uiState.update { it.copy(buttonEnabled = true) }
    }

    fun deviceEnabledFromDB(address: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAlwaysOnEnabled = cameraDeviceDAO.isDeviceAlwaysOnEnabled(address),
                    isDeviceEnabled = cameraDeviceDAO.isDeviceEnabled(address)
                )
            }
        }

    }

    fun setAlwaysOnEnabled(
        enabled: Boolean,
        device: AssociatedDeviceCompat,
        deviceManager: CompanionDeviceManager,
        associationId: Int,
        context: Context
    ) {
        viewModelScope.launch {
            cameraDeviceDAO.setAlwaysOnEnabled(device.address, enabled)
            val intent = Intent(context, LocationSenderService::class.java)
            intent.putExtra("address", device.address.uppercase())
            _uiState.update { it.copy(isAlwaysOnEnabled = enabled) }
            // TODO rethink if it is needed to stop the observation

            if (enabled) {
                context.startForegroundService(intent)
                /* if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                     deviceManager.stopObservingDevicePresence(
                         ObservingDevicePresenceRequest.Builder()
                             .setAssociationId(associationId).build()
                     )
                 } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                     deviceManager.stopObservingDevicePresence(device.address)
                 }*/
            } else {
                stopServiceWithDelay(context, device, deviceManager)
                // startDevicePresenceObservation(deviceManager, device)
            }

        }
    }
}

