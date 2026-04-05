package com.saschl.cameragps.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.CHARACTERISTIC_READ_UUID
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.locationTransmissionNotificationId
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.sasch.cameragps.sharednew.ui.settings.LocationProvider
import com.saschl.cameragps.R
import com.saschl.cameragps.notification.NotificationsHelper
import com.saschl.cameragps.utils.PreferencesManager
import com.saschl.cameragps.utils.SentryInit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger


/**
 * Service responsible for sending GPS location data to Sony cameras via Bluetooth
 */
class LocationSenderService : LifecycleService() {
    private var isLocationTransmitting: Boolean = false

    private var isInitialized = true

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Fallback location provider
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var usePlayServices: Boolean = true
    private var fallbackLocationHandler: Handler? = null
    private var fallbackLocationRunnable: Runnable? = null

    private var locationResult: Location? = null
    private var hasSessionLocation: Boolean = false
    private lateinit var eventSoundPlayer: EventSoundPlayer

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
            gattCallback = BluetoothGattCallbackHandler()
        )
    }

    private fun hasTimeZoneDstFlag(value: ByteArray): Boolean {
        return value.size >= 5 && (value[4].toInt() and 0x02) != 0
    }

    private lateinit var bluetoothStateReceiver: BluetoothStateBroadcastReceiver

    private val gattErrorCount = AtomicInteger(0)

    private val commandMutex = Mutex()


    companion object {
        val activeTransmissions = mutableStateMapOf<String, Boolean>()
    }

   // private val commandChannel = Channel<CommandData>(Channel.UNLIMITED)

    //data class CommandData(val intent: Intent?, val startId: Int)

    private fun resetLocationIfTooOld() {
        // Once we received a live fix during this session, keep using it even if no
        // newer updates arrive while the user is stationary.
        if (hasSessionLocation) return

        val currentLocation = locationResult ?: return
        if (isLocationTooOld(currentLocation)) {
            Timber.e("Last known location is older than 5 minutes, will try to get a fresh location before transmitting")
            locationResult = null
        }
    }

    private fun isLocationTooOld(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            location.elapsedRealtimeAgeMillis > SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS
        } else {
            (System.currentTimeMillis() - location.time) > SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS
        }
    }

    private fun markLocationAcquiredIfNeeded(hadLocationBefore: Boolean) {
        if (!hadLocationBefore) {
            eventSoundPlayer.play(TransmissionSoundEvent.LOCATION_ACQUIRED)
        }
    }

    private fun markLocationInvalid() {
        eventSoundPlayer.play(TransmissionSoundEvent.LOCATION_INVALID)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationTransmission() {
        if (!hasLocationPermissions()) {
            Timber.e("Location permissions missing, cannot start location transmission")
            return
        }
        if (!hasAnyLocationProviderEnabled()) {
            Timber.e("No location providers enabled, cannot start location transmission")
            return
        }

        if (!isLocationTransmitting) {
            resetLocationIfTooOld()
            Timber.i("Starting location transmission")
            if (locationResult != null) {
                Timber.i("Sending last known location to all active connections")
                cameraConnectionManager.getActiveConnections().forEach { device ->
                    sendData(device.gatt, device.writeCharacteristic, device.locationDataConfig)
                }
            }

            try {
                if (usePlayServices) {
                    startPlayServicesLocationUpdates()
                } else {
                    startFallbackLocationUpdates()
                }
                isLocationTransmitting = true
                // Start periodic transmission handler to ensure updates even when stationary
                startFallbackPeriodicTransmission()
            } catch (e: Exception) {
                Timber.e(e, "Failed to start location transmission")
                isLocationTransmitting = false
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startPlayServicesLocationUpdates() {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(this)
        if (resultCode != ConnectionResult.SUCCESS) {
            Timber.w("Google Play Services unavailable (code: $resultCode). Check location provider setting.")
            return
        }
        if (!::fusedLocationClient.isInitialized) {
            Timber.e("FusedLocationProviderClient not initialized")
            return
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
            if (location != null) {
                if (isLocationTooOld(location)) {
                    Timber.w("Ignoring stale initial location from Play Services")
                } else {
                    val hadLocationBefore = locationResult != null
                    locationResult = location
                    markLocationAcquiredIfNeeded(hadLocationBefore)
                    Timber.d("Sending initial location to all active connections")
                    cameraConnectionManager.getActiveConnections().forEach { device ->
                        sendData(device.gatt, device.writeCharacteristic, device.locationDataConfig)
                    }
                }
            }
        }.addOnFailureListener { e ->
            Timber.e(e, "Failed to get initial location from Play Services")
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL_MS,
        )
            .setWaitForAccurateLocation(false)
            .setMinUpdateDistanceMeters(10f)
            //.setMaxUpdateDelayMillis(60000)
            .build()

        val locationSettings = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)

        LocationServices.getSettingsClient(this).checkLocationSettings(locationSettings.build())
            .addOnSuccessListener {
                Timber.d("Location Settings are satisfied, starting location request")

                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
            }.addOnFailureListener { exception ->
                Timber.e(
                    exception,
                    "Location settings are not satisfied, cannot start location updates"
                )
            }

    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startFallbackLocationUpdates() {
        val locManager = locationManager ?: run {
            Timber.e("LocationManager not initialized")
            return
        }

        // Get last known location
        val lastKnownLocation = locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (lastKnownLocation != null) {
            if (isLocationTooOld(lastKnownLocation)) {
                Timber.w("Ignoring stale initial location from fallback provider")
            } else {
                val hadLocationBefore = locationResult != null
                locationResult = lastKnownLocation
                markLocationAcquiredIfNeeded(hadLocationBefore)
                Timber.d("Sending initial location from fallback provider to all active connections")
                cameraConnectionManager.getActiveConnections().forEach { device ->
                    sendData(device.gatt, device.writeCharacteristic, device.locationDataConfig)
                }
            }
        }

        // Create location listener
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Timber.d("Got a new location from fallback provider")
                if (shouldUpdateLocation(location)) {
                    val hadLocationBefore = locationResult != null
                    hasSessionLocation = true
                    locationResult = location
                    markLocationAcquiredIfNeeded(hadLocationBefore)
                    /* Timber.d("Will update cameras with new location")
                     cameraConnectionManager.getActiveConnections().forEach {
                         Timber.d("Sending location to camera ${it.gatt.device.name}")
                         sendData(it.gatt, it.writeCharacteristic, it.locationDataConfig)
                     }*/
                }
            }

            override fun onProviderEnabled(provider: String) {
                Timber.i("Location provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Timber.w("Location provider disabled: $provider")
            }
        }

        locationListener = listener

        // Use fused provider on Android 12+ (API 31), otherwise use GPS with Network as fallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ has fused location provider built into the platform
            if (locManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                locManager.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MS,
                    10f,
                    listener,
                    Looper.getMainLooper()
                )
                Timber.i("Started location updates from FUSED provider (Android 12+)")
            } else {
                Timber.w("FUSED provider not available, falling back to GPS")
                requestGpsLocationUpdates(locManager, listener)
            }
        } else {
            // Pre-Android 12: use GPS as primary
            requestGpsLocationUpdates(locManager, listener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGpsLocationUpdates(locManager: LocationManager, listener: LocationListener) {
        if (locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper()
            )
            Timber.i("Started location updates from GPS provider")
        } else if (locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            // Use Network provider as fallback if GPS is disabled
            locManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper()
            )
            Timber.i("GPS disabled, using Network provider as fallback")
        } else {
            Timber.e("No location providers available")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFallbackPeriodicTransmission() {
        // Create handler on main looper
        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                if (isLocationTransmitting) {
                    // Send last known location to all active cameras
                    if (locationResult != null) {
                        Timber.d("Periodic: Sending last known location to cameras")
                        cameraConnectionManager.getActiveConnections().forEach {
                            sendData(it.gatt, it.writeCharacteristic, it.locationDataConfig)
                        }
                    } else {
                        Timber.w("Periodic: No location available to send")
                        markLocationInvalid()
                    }

                    handler.postDelayed(this, LOCATION_UPDATE_INTERVAL_MS)
                }
            }
        }

        fallbackLocationHandler = handler
        fallbackLocationRunnable = runnable

        // Start periodic updates
        handler.postDelayed(runnable, LOCATION_UPDATE_INTERVAL_MS)
        Timber.d("Started periodic location transmission every ${LOCATION_UPDATE_INTERVAL_MS}ms")
    }

    private fun stopFallbackPeriodicTransmission() {
        fallbackLocationRunnable?.let { runnable ->
            fallbackLocationHandler?.removeCallbacks(runnable)
        }

        fallbackLocationHandler = null
        fallbackLocationRunnable = null
        Timber.d("Stopped periodic location transmission")
    }

    private fun shouldUpdateLocation(newLocation: Location): Boolean {
        // Any location is better than none initially
        if (locationResult == null) {
            return true
        }

        val accuracyDifference = newLocation.accuracy - locationResult!!.accuracy

        // If new location is significantly less accurate
        if (accuracyDifference > SonyBluetoothConstants.ACCURACY_THRESHOLD_METERS) {
            val timeDifference = newLocation.time - locationResult!!.time

            Timber.w("New location is way less accurate than the old one, will only update if the last location is older than 5 minutes")

            // Only update if the current location is very old
            if (timeDifference > SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS) {
                Timber.d("Last accurate location is older than 5 minutes, updating anyway")
                return true
            }
            markLocationInvalid()
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleNoAddress(startId: Int) {
       /* if (!startAsForegroundService()) {
            return
        }*/
        if (deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.i("No always-on devices found, shutting down service")
            requestShutdown(startId)
        } else {

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
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleGattConnectionFailure(startId: Int, cameraDevice: CameraDevice) {
        Timber.e("Failed to connect to device ${cameraDevice.deviceName}, bluetooth is likely disabled")
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleShutdownAllDevices(startId: Int) {
        if (deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.i("No always-on devices found, disconnecting all cameras and shutting down service")
            cameraConnectionManager.disconnectAll()
            requestShutdown(startId)
        } else {
            Timber.i("At least one always-on device found, not shutting down service")
        }

    }

    @SuppressLint("MissingPermission")
    private suspend fun handleShutdownRequest(address: String, startId: Int) {
        Timber.i("Shutdown requested for device $address")

        if (address == "all") {
            handleShutdownAllDevices(startId)
            return
        }

        // TODO, if a camera is still on and connected it will not shutdown the service, maybe we should disconnect the camera in that case and shut down if no active connections remain?


        // sometimes false "disappeared" events appear, so keep the device active if it was always on
        if (!deviceDao.isDeviceAlwaysOnEnabled(address)) {
            cameraConnectionManager.pauseDevice(address)
        }
        if (cameraConnectionManager.getActiveCameras().isEmpty() && deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.d("No connected or always on cameras remaining, shutting down service")
            requestShutdown(startId)
        }
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

        /**
         *  TODO maybe handle with commandqueue to avoid blocking the main thread (although all operations finish rather quickly)
         *
         * val commandQueue = Channel<CommandData>(Channel.UNLIMITED)
         *  data class CommandData(val intent: Intent?, val startId: Int)
         */

        //commandChannel.trySend(CommandData(intent, startId))

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

    @SuppressLint("MissingPermission")
    private suspend fun handleStartCommand(intent: Intent?, startId: Int) {
        val address = intent?.getStringExtra("address")
        val isShutdownRequest = intent?.action == SonyBluetoothConstants.ACTION_REQUEST_SHUTDOWN

        if (address == null) {
            handleNoAddress(startId)
            return
        }
        if (isShutdownRequest) {
            handleShutdownRequest(address, startId)
            return
        }

     /*   if (isLocationTransmitting || !startAsForegroundService()) {
            return
        }*/

        // shutdown service if bluetooth is turned off
        // could be potentially improved by just disabling the transmission and reconnect after bluetooth is on again
        if (!::bluetoothStateReceiver.isInitialized) {
            bluetoothStateReceiver = BluetoothStateBroadcastReceiver { enabled ->
                Timber.w("Bluetooth turned off, will shutdown service")
                if (!enabled) requestShutdown()
            }

            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            ContextCompat.registerReceiver(
                this,
                bluetoothStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        if (!cameraConnectionManager.isConnected(address)) {
            Timber.i("Service initialized")
            runCatching {
                cameraConnectionManager.connect(address)
            }.onFailure { Timber.e("Failed to connect to device, bluetooth is likely turned off") }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        if (isInitialized) {
            Timber.e("Service unexpectedly destroyed, attempting to restart")
            val broadcastIntent =
                Intent(applicationContext, RestartReceiver::class.java)
            broadcastIntent.putExtra("was_running", true)
            // FIXME find way without blocking call
            runBlocking {
                val hadAlwaysOnDevices =
                    deviceDao.getAlwaysOnEnabledDeviceCount() > 0
                broadcastIntent.putExtra("had_always_on_devices", hadAlwaysOnDevices)
                sendBroadcast(broadcastIntent)
            }
        }
        runCatching {
            if (::bluetoothStateReceiver.isInitialized) unregisterReceiver(bluetoothStateReceiver)
        }.onFailure { e ->
            Timber.e(e, "Failed to unregister Bluetooth state receiver")
        }
        activeTransmissions.clear()
        if (::locationCallback.isInitialized && usePlayServices) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        locationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
            locationListener = null
        }
        stopFallbackPeriodicTransmission()
        isLocationTransmitting = false
        hasSessionLocation = false

        cameraConnectionManager.disconnectAll()
        Timber.i("Destroyed service")
    }

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        eventSoundPlayer = EventSoundPlayer(this)

        NotificationsHelper.createNotificationChannel(this)

        if (!startAsForegroundService()) {
            return
        }

        initializeLogging()
        initializeLocationServices()
    }

    private fun initializeLocationServices() {
        val provider = PreferencesManager.getLocationProvider(this)
        usePlayServices = provider == LocationProvider.PLAY_SERVICES
        locationCallback = LocationUpdateHandler()

        if (usePlayServices) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            locationManager = getSystemService(LocationManager::class.java)

            val availability = GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(this)
            if (resultCode != ConnectionResult.SUCCESS) {
                Timber.w("Google Play Services unavailable (code: $resultCode). Check location provider setting.")
            } else {
                Timber.i("Google Play Services available, using FusedLocationProviderClient")
            }
        } else {
            Timber.i("Using platform LocationManager provider")
            locationManager = getSystemService(LocationManager::class.java)
        }
    }

    private fun initializeLogging() {
        if (Timber.forest().find { it is FileTree } == null) {
            FileTree.initialize(this)
            Timber.plant(FileTree(this, PreferencesManager.logLevel(this)))
            SentryInit.initSentry(this)

            // Set up global exception handler to log crashes
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))

        }
    }

    private inner class LocationUpdateHandler : LocationCallback() {
        @SuppressLint("MissingPermission")
        override fun onLocationResult(fetchedLocation: LocationResult) {
            super.onLocationResult(fetchedLocation)
            Timber.d("Got a new location")

            val lastLocation = fetchedLocation.lastLocation ?: return

            if (shouldUpdateLocation(lastLocation)) {
                val hadLocationBefore = locationResult != null
                hasSessionLocation = true
                locationResult = lastLocation
                markLocationAcquiredIfNeeded(hadLocationBefore)
            }
        }
    }

    private fun startAsForegroundService(): Boolean {

        try {
            // promote service to foreground service
            ServiceCompat.startForeground(
                this,
                locationTransmissionNotificationId,
                NotificationsHelper.buildNotification(
                    this, getString(R.string.app_standby_title),
                    getString(R.string.app_standby_content),
                    NotificationsHelper.NOTIFICATION_CHANNEL_ID
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

            )
        } catch (e: SecurityException) {
            Timber.e("Failed to start foreground service due to missing permissions: ${e.message}")
            isInitialized = false
            stopSelf()
            return false
        }

        return true
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
            if (::locationCallback.isInitialized && usePlayServices) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
            locationListener?.let { listener ->
                locationManager?.removeUpdates(listener)
                locationListener = null
            }
            stopFallbackPeriodicTransmission()
            isLocationTransmitting = false
            hasSessionLocation = false
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

    @SuppressLint("MissingPermission")
    private fun resumeLocationTransmission(gatt: BluetoothGatt) {
        val notification = NotificationsHelper.buildNotification(
            this,
            cameraConnectionManager.getActiveCameras().size
        )
        NotificationsHelper.showNotification(this, locationTransmissionNotificationId, notification)

        // value from official Sony app,  might be unused on Android >= 14
        //gatt.requestMtu(158)
        gatt.discoverServices()
    }


    private inner class BluetoothGattCallbackHandler : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            super.onConnectionStateChange(gatt, status, newState)

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Connected to device with status %d", status)
                cameraConnectionManager.resumeDevice(gatt.device.address.uppercase())
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
                eventSoundPlayer.play(TransmissionSoundEvent.CAMERA_DISCONNECTED)
                cancelLocationTransmission()
                return
            }

            Timber.d("Ignoring connection callback with status=$status and state=$newState")
        }

        /*     @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
             override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                 super.onMtuChanged(gatt, mtu, status)
                 gatt.discoverServices()
             }*/

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)

            if (characteristic.uuid.toString().uppercase().startsWith("0000DD01")) {
                Timber.w("Received characteristic change from camera: ${characteristic.uuid}, $value")
            } else {
                Timber.i("Received characteristic change from camera: ${characteristic.uuid}, $value")
            }
        }


        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            val service =
                gatt.services?.find { it.uuid == constructBleUUID(SonyBluetoothConstants.SERVICE_UUID) }


            val writeLocationCharacteristic =
                service?.getCharacteristic(constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_UUID))

            cameraConnectionManager.setWriteCharacteristic(
                gatt.device.address.uppercase(),
                writeLocationCharacteristic
            )
            handleServicesDiscovered(gatt, service)
        }

        @SuppressLint("MissingPermission")
        private fun handleServicesDiscovered(
            gatt: BluetoothGatt,
            service: BluetoothGattService?
        ) {
            val readCharacteristic =
                service?.getCharacteristic(constructBleUUID(CHARACTERISTIC_READ_UUID))
            if (readCharacteristic != null) {
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
                Timber.i("Enabling GPS characteristic: ${gpsEnableCharacteristic.uuid}")
                BluetoothGattUtils.writeCharacteristic(
                    gatt,
                    gpsEnableCharacteristic,
                    SonyBluetoothConstants.GPS_ENABLE_COMMAND
                )
            } else {
                Timber.i("Characteristic to enable GPS does not exist, starting transmission directly")
                startLocationTransmission()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            writtenCharacteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            super.onCharacteristicWrite(gatt, writtenCharacteristic, status)

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
                    startLocationTransmission()
                }

                constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_UUID) -> {
                    Timber.d("Location data sent to device, status was $status")
                    gattErrorCount.set(0)
                }

                else -> {
                    Timber.w("Unknown characteristic written: ${writtenCharacteristic?.uuid}, status was $status")
                }
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                val currentCount = gattErrorCount.incrementAndGet()
                Timber.w("Error writing characteristic: $status with count $currentCount")
                /*  if (currentCount > 50) {
                      Timber.e("Too many GATT errors, disconnecting from device")
                      cameraConnectionManager.pauseDevice(gatt.device.address.uppercase())
                      cancelLocationTransmission()
                  }*/
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
                startLocationTransmission()
                return
            }
            timeSyncCharacteristic.let {
                val timeSyncPacket =
                    LocationDataConverter.serializeTimeAreaData(ZonedDateTime.now())
                Timber.d("Sending time sync data to camera")

                if (!BluetoothGattUtils.writeCharacteristic(
                        gatt,
                        it,
                        timeSyncPacket
                    )
                ) {
                    Timber.e("Failed to send time sync data to camera, starting location transmission directly")
                    startLocationTransmission()
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun handleGpsEnableResponse(gatt: BluetoothGatt) {
            // The GPS command has been unlocked, now lock it for us
            val lockCharacteristic = BluetoothGattUtils.findCharacteristic(
                gatt,
                constructBleUUID(SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND)
            )

            lockCharacteristic?.let {
                Timber.i("Found characteristic to lock GPS: ${it.uuid}")
                BluetoothGattUtils.writeCharacteristic(
                    gatt,
                    it,
                    SonyBluetoothConstants.GPS_ENABLE_COMMAND
                )
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT])
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                doOnRead(characteristic.value, gatt, characteristic)
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            super.onCharacteristicRead(gatt, characteristic, value, status)
            doOnRead(value, gatt, characteristic)
        }

        // TODO make this a bit cleaner, right now we do not check for specific characteristics, but we only read one anyway
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
        private fun doOnRead(
            value: ByteArray,
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            cameraConnectionManager.setLocationDataConfig(
                gatt.device.address.uppercase(),
                LocationDataConfig(hasTimeZoneDstFlag(value))
            )

            Timber.i("Characteristic read, shouldSendTimeZoneAndDst: ${hasTimeZoneDstFlag(value)}")
            enableGpsTransmission(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendData(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?,
        locationDataConfig: LocationDataConfig
    ) {
        if (gatt == null || characteristic == null) {
            Timber.w("Cannot send data: GATT or characteristic is null")
            return
        }

        val locationPacket =
            LocationDataConverter.buildLocationDataPacket(locationDataConfig, locationResult!!)

        BluetoothGattUtils.writeCharacteristic(gatt, characteristic, locationPacket)

    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun requestShutdown(startId: Int? = null) {
        isInitialized = false
        if (startId != null) {
            stopSelf(startId)
        } else {
            stopSelf()
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasAnyLocationProviderEnabled(): Boolean {
        val manager = locationManager ?: getSystemService(LocationManager::class.java).also {
            locationManager = it
        }
        val gpsEnabled = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val networkEnabled = manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        return gpsEnabled || networkEnabled
    }

    private fun constructBleUUID(characteristic: String): UUID {
        return UUID.fromString(characteristic)
    }
}
