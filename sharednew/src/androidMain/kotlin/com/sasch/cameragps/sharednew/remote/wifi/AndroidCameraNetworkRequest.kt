package com.sasch.cameragps.sharednew.remote.wifi

import android.content.Context
import android.location.LocationManager
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Callback threads only enqueue events; the session owns and releases the request on Main. */
@RequiresApi(29)
internal class AndroidCameraNetworkRequest(context: Context, credentials: CameraWifiCredentials) : CameraNetworkRequest<Network> {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val channel = Channel<CameraNetworkEvent<Network>>(64)
    override val events = channel.receiveAsFlow()
    private var registered = false

    private fun emit(event: CameraNetworkEvent<Network>) {
        if (channel.trySend(event).isFailure) channel.close(WifiRemoteConnectException(WifiRemoteFailure.NetworkLost))
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = emit(CameraNetworkEvent.Available(network))
        override fun onUnavailable() = emit(CameraNetworkEvent.Unavailable)
        override fun onLost(network: Network) = emit(CameraNetworkEvent.Lost(network))
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            cameraGateway(properties)?.let { emit(CameraNetworkEvent.Ready(network, it)) }
        }
    }

    init {
        if (!context.getSystemService(WifiManager::class.java).isWifiEnabled) {
            throw WifiRemoteConnectException(WifiRemoteFailure.WifiDisabled)
        }
        if (Build.VERSION.SDK_INT <= 32 && !context.getSystemService(LocationManager::class.java).isLocationEnabled) {
            throw WifiRemoteConnectException(WifiRemoteFailure.LocationServicesRequired)
        }
        if (credentials.ssid.length !in 1..32 || credentials.password.length !in 8..63) {
            throw WifiRemoteConnectException(WifiRemoteFailure.CameraSetupFailed)
        }
        val specifier = WifiNetworkSpecifier.Builder().setSsid(credentials.ssid)
            .setWpa2Passphrase(credentials.password)
        credentials.bssid?.takeIf { it.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) }
            ?.let { bssid ->
                val mac = MacAddress.fromString(bssid)
                if (mac.addressType == MacAddress.TYPE_UNICAST && bssid != "00:00:00:00:00:00") specifier.setBssid(mac)
            }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier.build()).build()
        try {
            connectivity.requestNetwork(request, callback, 60_000)
            registered = true
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }

    override fun close() {
        if (registered) {
            registered = false
            try { connectivity.unregisterNetworkCallback(callback) }
            finally { channel.close() }
        }
    }
}

/** The camera is the gateway on its own AP, as in Creators' App's DHCP route lookup. */
internal fun cameraGateway(properties: LinkProperties): String? {
    val local = properties.linkAddresses.filter { it.address is Inet4Address }
    return properties.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway as? Inet4Address }
        .filter { gateway -> !gateway.isAnyLocalAddress && !gateway.isLoopbackAddress && !gateway.isMulticastAddress &&
            local.none { it.address == gateway } && local.any { IpPrefix(it.address, it.prefixLength).contains(gateway) } }
        .mapNotNull { it.hostAddress }.distinct().singleOrNull()
}
