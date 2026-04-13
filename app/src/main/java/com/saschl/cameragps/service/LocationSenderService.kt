package com.saschl.cameragps.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.locationTransmissionNotificationId
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.saschl.cameragps.R
import com.saschl.cameragps.notification.NotificationsHelper
import com.saschl.cameragps.service.coordinator.BleSessionCoordinator
import com.saschl.cameragps.service.coordinator.LocationTransmissionCoordinator
import com.saschl.cameragps.service.coordinator.RemoteControlCoordinator
import com.saschl.cameragps.service.coordinator.ServiceShutdownCoordinator
import com.saschl.cameragps.utils.PreferencesManager
import com.saschl.cameragps.utils.SentryInit
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger


/**
 * Thin Android lifecycle shell. Owns foreground service, notifications, sounds, and stopSelf().
 * All BLE/location/remote logic lives in coordinators that communicate via [ServiceEventBus].
 */
class LocationSenderService : LifecycleService() {

    // --- Android-only concerns ---
    private var isInitialized = true
    private lateinit var eventSoundPlayer: EventSoundPlayer
    private lateinit var bluetoothStateReceiver: BluetoothStateBroadcastReceiver
    private val gattErrorCount = AtomicInteger(0)
    private val commandMutex = Mutex()

    // --- shared wiring: eventBus is the single coupling point ---
    private val eventBus = ServiceEventBus()
    private val commandRouter = ServiceCommandRouter()

    private val deviceDao: CameraDeviceDAO by lazy {
        LogDatabase.getRoomDatabase(getDatabaseBuilder(applicationContext)).cameraDeviceDao()
    }

    private val bluetoothManager: BluetoothManager by lazy {
        applicationContext.getSystemService()!!
    }

    private val cameraConnectionManager by lazy {
        CameraConnectionManager(
            context = applicationContext,
            bluetoothManager = bluetoothManager,
            gattCallback = BluetoothGattCallbackHandler(),
        )
    }

    // --- coordinators: created once, no lambdas back to service ---
    private val remoteControlCoordinator by lazy {
        RemoteControlCoordinator(cameraConnectionManager, eventBus)
    }
    private val bleSessionCoordinator by lazy {
        BleSessionCoordinator(cameraConnectionManager, remoteControlCoordinator, eventBus)
    }
    private val locationTransmissionCoordinator by lazy {
        LocationTransmissionCoordinator(this, cameraConnectionManager, eventBus)
    }
    private val shutdownCoordinator by lazy {
        ServiceShutdownCoordinator(deviceDao, cameraConnectionManager, eventBus)
    }

    companion object {
        val activeTransmissions = mutableStateMapOf<String, Boolean>()
        val remoteFeatureActive = mutableStateMapOf<String, Boolean>()
        val sessionPhases = mutableStateMapOf<String, BleSessionPhase>()
    }

    // ==================== Lifecycle ====================

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        eventSoundPlayer = EventSoundPlayer(this)
        NotificationsHelper.createNotificationChannel(this)

        if (!startAsForegroundService()) return

        initializeLogging()
        locationTransmissionCoordinator.initializeLocationServices()

        // Single event collection loop — all side effects go here
        lifecycleScope.launch {
            eventBus.events.collect { event -> handleEvent(event) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            if (::bluetoothStateReceiver.isInitialized) unregisterReceiver(bluetoothStateReceiver)
        }.onFailure { e ->
            Timber.e(e, "Failed to unregister Bluetooth state receiver")
        }
        activeTransmissions.clear()
        remoteFeatureActive.clear()
        sessionPhases.clear()
        remoteControlCoordinator.cancelAllProbes()
        locationTransmissionCoordinator.shutdown()
        cameraConnectionManager.disconnectAll()
        Timber.i("Destroyed service")
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!bluetoothManager.adapter.isEnabled) {
            Timber.w("Bluetooth is disabled, will shutdown service")
            startAsForegroundService()
            requestShutdown(startId)
            return START_NOT_STICKY
        }

        lifecycleScope.launch {
            commandMutex.withLock {
                handleStartCommand(intent, startId)
                Timber.i(
                    "processed start command $startId with intent action ${intent?.action} and address ${
                        intent?.getStringExtra(
                            "address"
                        )
                    }"
                )
            }
        }
        return START_REDELIVER_INTENT
    }

    // ==================== Event handler — single place for side effects ====================

    @SuppressLint("MissingPermission")
    private fun handleEvent(event: ServiceEvent) {
        when (event) {
            is ServiceEvent.PhaseChanged -> {
                val normalized = event.address.uppercase()
                sessionPhases[normalized] = event.phase

                activeTransmissions[normalized] = event.phase == BleSessionPhase.Transmitting
                event.remoteActive?.let { remoteFeatureActive[normalized] = it }
            }

            is ServiceEvent.HandshakeComplete -> {
                val normalized = event.address.uppercase()
                sessionPhases[normalized] = BleSessionPhase.Transmitting
                activeTransmissions[normalized] = true
                locationTransmissionCoordinator.startTransmission()
            }

            is ServiceEvent.DeviceCleared -> {
                val normalized = event.address.uppercase()
                sessionPhases.remove(normalized)
                activeTransmissions.remove(normalized)
                remoteFeatureActive.remove(normalized)
                remoteControlCoordinator.cancelRemoteStatusProbe(normalized)
            }

            is ServiceEvent.AllDevicesCleared -> {
                sessionPhases.clear()
                activeTransmissions.clear()
                remoteFeatureActive.clear()
                remoteControlCoordinator.cancelAllProbes()
            }

            is ServiceEvent.FirstLocationAcquired -> {
                eventSoundPlayer.play(TransmissionSoundEvent.LOCATION_ACQUIRED)
            }

            is ServiceEvent.LocationInvalid -> {
                eventSoundPlayer.play(TransmissionSoundEvent.LOCATION_INVALID)
            }

            is ServiceEvent.RequestShutdown -> {
                requestShutdown(event.startId)
            }

            is ServiceEvent.RemoteFeatureActivated -> {
                remoteFeatureActive[event.address.uppercase()] = true
            }

            is ServiceEvent.RemoteFeatureDeactivated -> {
                remoteFeatureActive[event.address.uppercase()] = false
            }
        }
    }

    // ==================== Command handling ====================

    @SuppressLint("MissingPermission")
    private suspend fun handleStartCommand(intent: Intent?, startId: Int) {
        when (val command = commandRouter.route(intent)) {
            is ServiceCommand.Ignore -> {
                Timber.w(command.reason)
            }

            is ServiceCommand.ReconnectAlwaysOn -> {
                shutdownCoordinator.handleNoAddress(startId)
            }

            is ServiceCommand.Shutdown -> {
                shutdownCoordinator.handleShutdownRequest(command.address, startId)
            }

            is ServiceCommand.TriggerRemoteShutter -> {
                bleSessionCoordinator.handleRemoteShutterRequest(command.address)
            }

            is ServiceCommand.Connect -> {
                ensureBluetoothStateReceiver()
                if (!cameraConnectionManager.isConnected(command.address)) {
                    Timber.i("Service initialized")
                    eventBus.emit(
                        ServiceEvent.PhaseChanged(
                            command.address,
                            BleSessionPhase.Connecting
                        )
                    )
                    runCatching {
                        cameraConnectionManager.connect(command.address)
                    }.onFailure {
                        Timber.e("Failed to connect to device, bluetooth is likely turned off")
                        eventBus.emit(
                            ServiceEvent.PhaseChanged(
                                command.address,
                                BleSessionPhase.Error
                            )
                        )
                    }
                }
            }
        }
    }

    private fun ensureBluetoothStateReceiver() {
        if (!::bluetoothStateReceiver.isInitialized) {
            bluetoothStateReceiver = BluetoothStateBroadcastReceiver { enabled ->
                Timber.w("Bluetooth turned off, will shutdown service")
                if (!enabled) eventBus.emit(ServiceEvent.RequestShutdown(null))
            }
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            ContextCompat.registerReceiver(
                this,
                bluetoothStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    // ==================== GATT callback — pure delegation ====================

    private inner class BluetoothGattCallbackHandler : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Connected to device with status %d", status)
                cameraConnectionManager.resumeDevice(gatt.device.address.uppercase())
                eventBus.emit(
                    ServiceEvent.PhaseChanged(
                        gatt.device.address,
                        BleSessionPhase.Connected
                    )
                )
                eventSoundPlayer.play(TransmissionSoundEvent.CAMERA_CONNECTED)
                resumeLocationTransmission(gatt)
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                if (status == 19 || status == 8) {
                    Timber.i("Device disconnected in callback due to device turned off or out of range: $status")
                } else {
                    Timber.e("An error happened: $status")
                }
                cameraConnectionManager.pauseDevice(gatt.device.address.uppercase())
                eventBus.emit(ServiceEvent.DeviceCleared(gatt.device.address))
                eventSoundPlayer.play(TransmissionSoundEvent.CAMERA_DISCONNECTED)
                cancelLocationTransmission()
                return
            }

            Timber.d("Ignoring connection callback with status=$status and state=$newState")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            bleSessionCoordinator.onCharacteristicChanged(gatt, characteristic, value)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            bleSessionCoordinator.onServicesDiscovered(gatt, status)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            writtenCharacteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, writtenCharacteristic, status)
            bleSessionCoordinator.onCharacteristicWrite(gatt, writtenCharacteristic, status)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                gattErrorCount.incrementAndGet()
            } else if (writtenCharacteristic?.uuid == constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_UUID)) {
                gattErrorCount.set(0)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            bleSessionCoordinator.onDescriptorWrite(gatt, descriptor, status)
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                bleSessionCoordinator.onCharacteristicRead(gatt, characteristic.value, status)
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, value, status)
            bleSessionCoordinator.onCharacteristicRead(gatt, value, status)
        }
    }

    // ==================== Notification helpers ====================

    @SuppressLint("MissingPermission")
    private fun resumeLocationTransmission(gatt: BluetoothGatt) {
        eventBus.emit(
            ServiceEvent.PhaseChanged(
                gatt.device.address,
                BleSessionPhase.DiscoveringServices
            )
        )
        val notification = NotificationsHelper.buildNotification(
            this,
            cameraConnectionManager.getActiveCameras().size
        )
        NotificationsHelper.showNotification(this, locationTransmissionNotificationId, notification)
        gatt.discoverServices()
    }

    private fun cancelLocationTransmission() {
        if (cameraConnectionManager.getActiveCameras().isEmpty()) {
            val notification = NotificationsHelper.buildNotification(
                this,
                getString(R.string.app_standby_title),
                getString(R.string.app_standby_content)
            )
            NotificationsHelper.showNotification(
                this,
                locationTransmissionNotificationId,
                notification
            )
            Timber.d("No active cameras remaining, stopping location updates")
            locationTransmissionCoordinator.stopTransmissionIfNoActiveCameras(noActiveCameras = true)
        } else {
            Timber.d("Active cameras remaining, updating notification")
            val notification = NotificationsHelper.buildNotification(
                this,
                cameraConnectionManager.getActiveCameras().size,
                channelId = NotificationsHelper.DISCONNECT_NOTIFICATION_CHANNEL
            )
            NotificationsHelper.showNotification(
                this,
                locationTransmissionNotificationId,
                notification
            )
        }
    }

    private fun startAsForegroundService(): Boolean {
        try {
            ServiceCompat.startForeground(
                this,
                locationTransmissionNotificationId,
                NotificationsHelper.buildNotification(
                    this,
                    getString(R.string.app_standby_title),
                    getString(R.string.app_standby_content),
                    NotificationsHelper.NOTIFICATION_CHANNEL_ID
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: SecurityException) {
            Timber.e("Failed to start foreground service due to missing permissions: ${e.message}")
            isInitialized = false
            stopSelf()
            return false
        }
        return true
    }

    // ==================== Utilities ====================

    private fun initializeLogging() {
        if (Timber.forest().find { it is FileTree } == null) {
            FileTree.initialize(this)
            Timber.plant(FileTree(this, PreferencesManager.logLevel(this)))
            SentryInit.initSentry(this)
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun requestShutdown(startId: Int? = null) {
        isInitialized = false
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    private fun constructBleUUID(characteristic: String): UUID = UUID.fromString(characteristic)
}
