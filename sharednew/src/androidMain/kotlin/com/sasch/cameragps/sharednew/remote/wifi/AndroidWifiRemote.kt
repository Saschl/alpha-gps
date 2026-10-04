package com.sasch.cameragps.sharednew.remote.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.net.InetAddress

fun createAndroidWifiRemoteController(context: Context, scope: CoroutineScope,
                                      orchestrator: CameraSessionOrchestrator): WifiRemoteController {
    val connector = AndroidWifiRemoteConnector(context.applicationContext)
    val automatic = if (Build.VERSION.SDK_INT >= 29) AutomaticWifiRemoteConnector(
        orchestrator::prepareCameraWifi,
        { credentials -> AndroidCameraNetworkRequest(context.applicationContext, credentials) },
        connector::openOnNetwork,
    ) else null
    val manualSetup = if (Build.VERSION.SDK_INT < 29) AndroidManualWifiSetup(
        context.applicationContext, orchestrator::prepareCameraWifi, connector::openOnNetwork,
    ) else null
    return WifiRemoteController(scope, orchestrator.registry, connector,
        orchestrator::claimWifiControls, orchestrator::releaseWifiControls,
        automatic?.let { delegate -> WifiAutomaticConnector { id, sessionScope, phase ->
            try { delegate.open(id, sessionScope, phase) }
            catch (_: SecurityException) { throw WifiRemoteConnectException(WifiRemoteFailure.NetworkPermissionDenied) }
        }
        }, manualSetup
    )
}

private class AndroidWifiRemoteConnector(context: Context) : WifiRemoteConnector {
    private val imageStore = AndroidCameraImageStore(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override suspend fun open(host: String, scope: CoroutineScope): WifiRemoteConnection {
        return try { openCamera(host, scope) }
        catch (_: SecurityException) { throw WifiRemoteConnectException(WifiRemoteFailure.NetworkPermissionDenied) }
    }

    private suspend fun openCamera(host: String, scope: CoroutineScope): WifiRemoteConnection {
        try { SonyLiveViewEndpoint.fromUrl("http://$host/", host) }
        catch (_: IllegalArgumentException) { throw WifiRemoteConnectException(WifiRemoteFailure.InvalidAddress) }
        val candidates = connectivity.allNetworks.filter { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (candidates.size != 1) throw WifiRemoteConnectException(WifiRemoteFailure.JoinCameraWifi)
        val network = candidates.single()
        return openOnNetwork(network, host, scope)
    }

    suspend fun openOnNetwork(network: Network, host: String, scope: CoroutineScope): WifiRemoteConnection {
        val address = InetAddress.getByName(host) // Validated literal; never performs a hostname lookup.
        val links = connectivity.getLinkProperties(network)
            ?: throw WifiRemoteConnectException(WifiRemoteFailure.NetworkLost)
        if (links.linkAddresses.none { link ->
                android.net.IpPrefix(link.address, link.prefixLength).contains(address)
            } || links.linkAddresses.any { it.address == address }) {
            throw WifiRemoteConnectException(WifiRemoteFailure.JoinCameraWifi)
        }
        val losses = Channel<Unit>(Channel.CONFLATED)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) { if (lost == network) losses.trySend(Unit) }
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        if (connectivity.getLinkProperties(network) == null) {
            connectivity.unregisterNetworkCallback(callback)
            losses.close()
            throw WifiRemoteConnectException(WifiRemoteFailure.NetworkLost)
        }
        return openSonyWifiSession(
            AndroidPtpIpConnectionFactory(network, host), scope, host,
            AndroidCameraHttpTransport(network.socketFactory), imageStore,
            ::decodeAndroidCameraThumbnail, losses.receiveAsFlow(),
            releaseNetwork = { connectivity.unregisterNetworkCallback(callback); losses.close() },
        )
    }
}
