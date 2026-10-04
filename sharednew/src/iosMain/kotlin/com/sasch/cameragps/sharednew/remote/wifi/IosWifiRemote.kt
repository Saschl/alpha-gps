package com.sasch.cameragps.sharednew.remote.wifi

import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun createIosWifiRemoteController(
    scope: CoroutineScope,
    orchestrator: CameraSessionOrchestrator,
): WifiRemoteController {
    val connector = IosWifiRemoteConnector()
    val automatic = AutomaticWifiRemoteConnector(
        orchestrator::prepareCameraWifi,
        { credentials -> IosCameraNetworkRequest(credentials, scope) },
        { _: Unit, host, sessionScope -> connector.open(host, sessionScope) },
    )
    return WifiRemoteController(scope, orchestrator.registry, connector,
        orchestrator::claimWifiControls, orchestrator::releaseWifiControls, automatic)
}

private class IosWifiRemoteConnector : WifiRemoteConnector {
    private val imageStore = IosCameraImageStore()
    override suspend fun open(host: String, scope: CoroutineScope): WifiRemoteConnection {
        try { SonyLiveViewEndpoint.fromUrl("http://$host/", host) }
        catch (_: IllegalArgumentException) { throw WifiRemoteConnectException(WifiRemoteFailure.InvalidAddress) }
        return withIosCommandConnection(
            connect = { CameraPtpIpConnection(IosCameraByteConnection.open(host, 15740)) },
        ) { connections ->
            openSonyWifiSession(connections, scope, host,
                CameraHttpTransport(IosCameraByteConnection::open), imageStore,
                ::decodeIosCameraThumbnail, ::decodeIosCameraPreview
            )
        }
    }
}

/** Resolve the iOS permission prompt before the shared Sony handshake timeout starts. */
internal suspend fun <T> withIosCommandConnection(
    connect: suspend () -> PtpIpPacketConnection,
    openSession: suspend (PtpIpConnectionFactory) -> T,
): T {
    val command = connect()
    var consumed = false
    var transferred = false
    try {
        return openSession(PtpIpConnectionFactory {
            if (!consumed) {
                consumed = true
                command
            } else connect()
        }).also { transferred = consumed }
    } finally {
        if (!transferred) withContext(NonCancellable) { command.close() }
    }
}
