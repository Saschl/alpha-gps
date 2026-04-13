package com.saschl.cameragps.service.coordinator

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS
import com.sasch.cameragps.sharednew.ui.settings.LocationProvider
import com.saschl.cameragps.service.BluetoothGattUtils
import com.saschl.cameragps.service.CameraConnectionManager
import com.saschl.cameragps.service.LocationDataConverter
import com.saschl.cameragps.service.ServiceEvent
import com.saschl.cameragps.service.ServiceEventBus
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber

class LocationTransmissionCoordinator(
    private val context: Context,
    private val cameraConnectionManager: CameraConnectionManager,
    private val eventBus: ServiceEventBus,
) {
    private var isLocationTransmitting: Boolean = false
    private var hasSessionLocation: Boolean = false
    private var usePlayServices: Boolean = true
    private var locationResult: Location? = null

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var locationCallback: LocationCallback? = null

    private var fallbackLocationHandler: Handler? = null
    private var fallbackLocationRunnable: Runnable? = null

    fun initializeLocationServices() {
        usePlayServices =
            PreferencesManager.getLocationProvider(context) == LocationProvider.PLAY_SERVICES

        if (usePlayServices) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            locationManager = context.getSystemService(LocationManager::class.java)

            val availability = GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(context)
            if (resultCode != ConnectionResult.SUCCESS) {
                Timber.w("Google Play Services unavailable (code: $resultCode). Check location provider setting.")
            } else {
                Timber.i("Google Play Services available, using FusedLocationProviderClient")
            }
        } else {
            Timber.i("Using platform LocationManager provider")
            locationManager = context.getSystemService(LocationManager::class.java)
        }

        locationCallback = object : LocationCallback() {
            @SuppressLint("MissingPermission")
            override fun onLocationResult(fetchedLocation: LocationResult) {
                super.onLocationResult(fetchedLocation)
                Timber.d("Got a new location")

                val lastLocation = fetchedLocation.lastLocation ?: return
                if (shouldUpdateLocation(lastLocation, locationResult)) {
                    val hadLocationBefore = locationResult != null
                    hasSessionLocation = true
                    locationResult = lastLocation
                    if (!hadLocationBefore) eventBus.emit(ServiceEvent.FirstLocationAcquired)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startTransmission() {
        if (!hasAnyLocationProviderEnabled()) {
            Timber.e("No location providers enabled, cannot start location transmission")
            return
        }

        if (isLocationTransmitting) return

        locationResult = resetLocationIfTooOld(hasSessionLocation, locationResult)
        Timber.i("Starting location transmission")

        locationResult?.let {
            Timber.i("Sending last known location to all active connections")
            sendLocationToActiveConnections(it)
        }

        try {
            if (usePlayServices) {
                startPlayServicesLocationUpdates()
            } else {
                startFallbackLocationUpdates()
            }
            isLocationTransmitting = true
            startFallbackPeriodicTransmission()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start location transmission")
            isLocationTransmitting = false
        }
    }

    fun stopTransmissionIfNoActiveCameras(noActiveCameras: Boolean): Boolean {
        if (!noActiveCameras) return false

        locationCallback?.let { callback ->
            if (usePlayServices) {
                fusedLocationClient?.removeLocationUpdates(callback)
            }
        }

        locationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
            locationListener = null
        }

        stopFallbackPeriodicTransmission()
        isLocationTransmitting = false
        hasSessionLocation = false
        return true
    }

    fun shutdown() {
        stopTransmissionIfNoActiveCameras(noActiveCameras = true)
        locationResult = null
    }

    // --- private helpers ---

    private fun sendLocationToActiveConnections(location: Location) {
        cameraConnectionManager.getActiveConnections().forEach { device ->
            if (device.gatt != null && device.writeCharacteristic != null) {
                val packet = LocationDataConverter.buildLocationDataPacket(
                    device.locationDataConfig,
                    location
                )
                BluetoothGattUtils.writeCharacteristic(
                    device.gatt,
                    device.writeCharacteristic,
                    packet
                )
            }
        }
    }

    private fun resetLocationIfTooOld(hasSessionLocation: Boolean, location: Location?): Location? {
        if (hasSessionLocation) return location
        val currentLocation = location ?: return null
        return if (isLocationTooOld(currentLocation)) null else currentLocation
    }

    private fun isLocationTooOld(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            location.elapsedRealtimeAgeMillis > SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS
        } else {
            (System.currentTimeMillis() - location.time) > SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS
        }
    }

    private fun shouldUpdateLocation(newLocation: Location, currentLocation: Location?): Boolean {
        currentLocation ?: return true

        val accuracyDifference = newLocation.accuracy - currentLocation.accuracy
        if (accuracyDifference <= SonyBluetoothConstants.ACCURACY_THRESHOLD_METERS) {
            return true
        }

        val timeDifference = newLocation.time - currentLocation.time
        return timeDifference > SonyBluetoothConstants.OLD_LOCATION_THRESHOLD_MS
    }

    @SuppressLint("MissingPermission")
    private fun startPlayServicesLocationUpdates() {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(context)
        if (resultCode != ConnectionResult.SUCCESS) {
            Timber.w("Google Play Services unavailable (code: $resultCode). Check location provider setting.")
            return
        }

        val fusedClient = fusedLocationClient
        if (fusedClient == null) {
            Timber.e("FusedLocationProviderClient not initialized")
            return
        }

        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    if (isLocationTooOld(location)) {
                        Timber.w("Ignoring stale initial location from Play Services")
                    } else {
                        val hadLocationBefore = locationResult != null
                        locationResult = location
                        if (!hadLocationBefore) eventBus.emit(ServiceEvent.FirstLocationAcquired)
                        Timber.d("Sending initial location to all active connections")
                        sendLocationToActiveConnections(location)
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
            .build()

        val callback = locationCallback ?: return
        val locationSettings = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)

        LocationServices.getSettingsClient(context).checkLocationSettings(locationSettings.build())
            .addOnSuccessListener {
                Timber.d("Location Settings are satisfied, starting location request")
                fusedClient.requestLocationUpdates(
                    locationRequest,
                    callback,
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
    private fun startFallbackLocationUpdates() {
        val locManager = locationManager ?: run {
            Timber.e("LocationManager not initialized")
            return
        }

        val lastKnownLocation = locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (lastKnownLocation != null) {
            if (isLocationTooOld(lastKnownLocation)) {
                Timber.w("Ignoring stale initial location from fallback provider")
            } else {
                val hadLocationBefore = locationResult != null
                locationResult = lastKnownLocation
                if (!hadLocationBefore) eventBus.emit(ServiceEvent.FirstLocationAcquired)
                Timber.d("Sending initial location from fallback provider to all active connections")
                sendLocationToActiveConnections(lastKnownLocation)
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Timber.d("Got a new location from fallback provider")
                if (shouldUpdateLocation(location, locationResult)) {
                    val hadLocationBefore = locationResult != null
                    hasSessionLocation = true
                    locationResult = location
                    if (!hadLocationBefore) eventBus.emit(ServiceEvent.FirstLocationAcquired)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (locManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                locManager.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MS,
                    10f,
                    listener,
                    Looper.getMainLooper(),
                )
                Timber.i("Started location updates from FUSED provider (Android 12+)")
            } else {
                Timber.w("FUSED provider not available, falling back to GPS")
                requestGpsLocationUpdates(locManager, listener)
            }
        } else {
            requestGpsLocationUpdates(locManager, listener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGpsLocationUpdates(locManager: LocationManager, listener: LocationListener) {
        if (locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            Timber.i("Started location updates from GPS provider")
        } else if (locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            Timber.i("GPS disabled, using Network provider as fallback")
        } else {
            Timber.e("No location providers available")
        }
    }

    private fun startFallbackPeriodicTransmission() {
        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                if (isLocationTransmitting) {
                    val currentLocation = locationResult
                    if (currentLocation != null) {
                        Timber.d("Periodic: Sending last known location to cameras")
                        sendLocationToActiveConnections(currentLocation)
                    } else {
                        Timber.w("Periodic: No location available to send")
                        eventBus.emit(ServiceEvent.LocationInvalid)
                    }

                    handler.postDelayed(this, LOCATION_UPDATE_INTERVAL_MS)
                }
            }
        }

        fallbackLocationHandler = handler
        fallbackLocationRunnable = runnable
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

    private fun hasAnyLocationProviderEnabled(): Boolean {
        val manager =
            locationManager ?: context.getSystemService(LocationManager::class.java).also {
                locationManager = it
            }
        val gpsEnabled = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val networkEnabled = manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        return gpsEnabled || networkEnabled
    }
}

