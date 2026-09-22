package com.sasch.cameragps.sharednew.remote.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
import java.net.InetAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.random.Random

fun createAndroidWifiRemoteController(context: Context, scope: CoroutineScope,
                                      orchestrator: CameraSessionOrchestrator): WifiRemoteController =
    WifiRemoteController(scope, orchestrator.registry, AndroidWifiRemoteConnector(context.applicationContext),
        orchestrator::claimWifiControls, orchestrator::releaseWifiControls)

private class AndroidWifiRemoteConnector(context: Context) : WifiRemoteConnector {
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
        val protocolScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        var session: PtpIpOpenedSession? = null
        try {
            if (connectivity.getNetworkCapabilities(network) == null) {
                throw WifiRemoteConnectException(WifiRemoteFailure.NetworkLost)
            }
            val opened = PtpIpSessionOpener(AndroidPtpIpConnectionFactory(network, host), protocolScope)
                .open(Random.nextBytes(16), "Alpha GPS")
                ?: throw WifiRemoteConnectException(WifiRemoteFailure.CameraUnavailable)
            session = opened
            val ready = when (val init = opened.initializeSony()) {
                is SonyPtpInitializationResult.Ready -> init
                is SonyPtpInitializationResult.Rejected -> throw WifiRemoteConnectException(WifiRemoteFailure.CameraRefused)
                else -> throw WifiRemoteConnectException(WifiRemoteFailure.ProtocolError)
            }
            val shutter = SonyPtpShutter(opened.commands, ready, opened.events)
            val preview = SonyLiveViewStream(opened.commands, ready, opened.events,
                AndroidCameraHttpTransport(network.socketFactory))
            return object : WifiRemoteConnection {
                override val cameraName = opened.camera.cameraName
                override val canCapture = ready.deviceInfo.supports(SonyPtpOperation.SDIO_CONTROL_DEVICE) &&
                    ready.extendedInfo.supportsControl(SonyPtpControlCode.HALF_PRESS) &&
                    ready.extendedInfo.supportsControl(SonyPtpControlCode.FULL_PRESS)
                override val images = flow {
                    val dd = ready.deviceDescription ?: error("Camera has no preview endpoint")
                    emitAll(preview.images(SonyLiveViewEndpoint.fromDeviceDescription(dd, host)))
                }
                override val lost = merge(losses.receiveAsFlow(), opened.events.isClosed.filter { it }.map { Unit })
                override suspend fun capture() = when (shutter.captureStill()) {
                    SonyPtpCaptureResult.CaptureEventObserved -> WifiCaptureStatus.Captured
                    is SonyPtpCaptureResult.Rejected, SonyPtpCaptureResult.Unsupported -> WifiCaptureStatus.Rejected
                    else -> WifiCaptureStatus.Uncertain
                }
                override suspend fun close() {
                    try { opened.close() }
                    finally { protocolScope.cancel(); connectivity.unregisterNetworkCallback(callback); losses.close() }
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { session?.close() }
                finally { protocolScope.cancel(); connectivity.unregisterNetworkCallback(callback); losses.close() }
            }
            throw failure
        }
    }
}
