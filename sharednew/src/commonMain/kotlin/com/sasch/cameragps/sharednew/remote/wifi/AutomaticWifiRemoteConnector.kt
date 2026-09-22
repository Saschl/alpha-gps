package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal sealed interface CameraNetworkEvent<out N> {
    data class Available<N>(val network: N) : CameraNetworkEvent<N>
    data class Ready<N>(val network: N, val host: String) : CameraNetworkEvent<N>
    data class Lost<N>(val network: N) : CameraNetworkEvent<N>
    data object Unavailable : CameraNetworkEvent<Nothing>
}

internal interface CameraNetworkRequest<N> {
    val events: Flow<CameraNetworkEvent<N>>
    fun close()
}

/** Owns the OS network request until the camera session has finished releasing its controls. */
internal class AutomaticWifiRemoteConnector<N>(
    private val prepare: suspend (String) -> CameraWifiCredentials?,
    private val request: (CameraWifiCredentials) -> CameraNetworkRequest<N>,
    private val openCamera: suspend (N, String, CoroutineScope) -> WifiRemoteConnection,
    private val joinTimeoutMs: Long = 60_000,
) : WifiAutomaticConnector {
    override suspend fun open(identifier: String, scope: CoroutineScope,
                              onPhase: (WifiRemotePhase) -> Unit): WifiRemoteConnection {
        onPhase(WifiRemotePhase.PreparingCamera)
        val requested = request(prepare(identifier)
            ?: throw WifiRemoteConnectException(WifiRemoteFailure.CameraSetupFailed))
        var connection: WifiRemoteConnection? = null
        try {
            onPhase(WifiRemotePhase.AwaitingNetworkApproval)
            var available = false
            val ready = withTimeoutOrNull(joinTimeoutMs) {
                requested.events.first { event ->
                    when (event) {
                        is CameraNetworkEvent.Available -> {
                            available = true
                            onPhase(WifiRemotePhase.JoiningNetwork)
                            false
                        }
                        is CameraNetworkEvent.Ready -> true
                        is CameraNetworkEvent.Lost -> throw WifiRemoteConnectException(WifiRemoteFailure.NetworkLost)
                        CameraNetworkEvent.Unavailable -> throw WifiRemoteConnectException(WifiRemoteFailure.NetworkJoinFailed)
                    }
                } as CameraNetworkEvent.Ready<N>
            } ?: throw WifiRemoteConnectException(if (available) WifiRemoteFailure.CameraAddressUnavailable
                else WifiRemoteFailure.NetworkJoinFailed)
            onPhase(WifiRemotePhase.OpeningSession)
            val opened = openCamera(ready.network, ready.host, scope)
            connection = opened
            return object : WifiRemoteConnection by opened {
                override val lost = merge(opened.lost, requested.events.filter {
                    it == CameraNetworkEvent.Unavailable || it is CameraNetworkEvent.Lost && it.network == ready.network
                }.map { Unit })
                override suspend fun close() {
                    try { opened.close() } finally { requested.close() }
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { connection?.close() } finally { requested.close() }
            }
            throw failure
        }
    }
}
