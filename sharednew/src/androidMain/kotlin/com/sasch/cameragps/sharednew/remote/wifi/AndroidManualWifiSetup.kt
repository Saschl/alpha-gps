package com.sasch.cameragps.sharednew.remote.wifi

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope

/** Android 8–9: the user joins in Settings; only inspect the resulting connection. */
internal class AndroidManualWifiSetup(
    context: Context,
    private val prepareCamera: suspend (String) -> CameraWifiCredentials?,
    private val openCamera: suspend (Network, String, CoroutineScope) -> WifiRemoteConnection,
) : WifiManualSetupConnector {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)

    override suspend fun prepare(identifier: String) = try {
        prepareCamera(identifier)
    } catch (_: SecurityException) {
        throw WifiRemoteConnectException(WifiRemoteFailure.NetworkPermissionDenied)
    }

    override suspend fun open(
        credentials: CameraWifiCredentials,
        scope: CoroutineScope
    ): WifiRemoteConnection = try {
        openJoinedNetwork(credentials, scope)
    } catch (_: SecurityException) {
        throw WifiRemoteConnectException(WifiRemoteFailure.NetworkPermissionDenied)
    }

    @Suppress("DEPRECATION")
    private suspend fun openJoinedNetwork(
        credentials: CameraWifiCredentials,
        scope: CoroutineScope
    ): WifiRemoteConnection {
        if (!wifi.isWifiEnabled) throw WifiRemoteConnectException(WifiRemoteFailure.WifiDisabled)
        if (!location.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            throw WifiRemoteConnectException(WifiRemoteFailure.LocationServicesRequired)
        }
        val info = wifi.connectionInfo
        if (!matchesCameraWifi(credentials, info?.ssid, info?.bssid)) {
            throw WifiRemoteConnectException(WifiRemoteFailure.JoinCameraWifi)
        }
        val network = connectivity.allNetworks.filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.singleOrNull() ?: throw WifiRemoteConnectException(WifiRemoteFailure.JoinCameraWifi)
        val host = connectivity.getLinkProperties(network)?.let(::cameraGateway)
            ?: throw WifiRemoteConnectException(WifiRemoteFailure.CameraAddressUnavailable)
        return openCamera(network, host, scope)
    }
}

internal fun matchesCameraWifi(
    credentials: CameraWifiCredentials,
    ssid: String?,
    bssid: String?
): Boolean {
    if (ssid?.removeSurrounding("\"") != credentials.ssid) return false
    val expectedBssid = credentials.bssid?.takeIf {
        it.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) && it != "00:00:00:00:00:00"
    }
    return expectedBssid == null || expectedBssid.equals(bssid, ignoreCase = true)
}
