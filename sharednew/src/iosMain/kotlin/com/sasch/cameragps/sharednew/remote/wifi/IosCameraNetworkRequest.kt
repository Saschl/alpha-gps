@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sasch.cameragps.sharednew.remote.wifi

import com.diamondedge.logging.logging
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Network.*
import platform.NetworkExtension.*
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState.UIApplicationStateActive
import platform.darwin.dispatch_get_main_queue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** NEHotspotConfiguration's completion confirms configuration, not camera reachability. */
internal class IosCameraNetworkRequest(
    credentials: CameraWifiCredentials,
    scope: CoroutineScope,
) : CameraNetworkRequest<Unit> {
    private val log = logging()
    private val eventsChannel = Channel<CameraNetworkEvent<Unit>>(64)
    override val events = eventsChannel.receiveAsFlow()
    private val ssid = credentials.ssid
    private var closed = false
    private var applied = false
    private var joining = true
    private val manager = NEHotspotConfigurationManager.sharedManager
    private val monitor = nw_path_monitor_create_with_type(nw_interface_type_wifi)
    private val paths = Channel<String?>(Channel.CONFLATED)
    private val job: Job

    init {
        require(ssid.isNotEmpty() && ssid.encodeToByteArray().size <= 32 && credentials.password.length in 8..63)
        val configuration = NEHotspotConfiguration(sSID = ssid, passphrase = credentials.password, isWEP = false).apply { joinOnce = true }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_set_update_handler(monitor) { path ->
            val gateways = mutableListOf<String>()
            if (nw_path_get_status(path) == nw_path_status_satisfied) {
                nw_path_enumerate_gateways(path) { endpoint ->
                    nw_endpoint_get_hostname(endpoint)?.toKString()?.let { gateways += it }
                    true
                }
            }
            val host = iosCameraGateway(gateways)
            log.i { "Wi-Fi iOS path status=${nw_path_get_status(path)} gateway=$host" }
            paths.trySend(host)
        }
        job = scope.launch {
            try {
                joinMutex.withLock {
                    if (closed) return@launch
                    val completion = Channel<Long?>(1)
                    log.i { "Wi-Fi iOS requesting temporary camera network join" }
                    manager.applyConfiguration(configuration) { error -> completion.trySend(error?.code) }
                    val code = completion.receive()
                    completion.close()
                    log.i { "Wi-Fi iOS join completed errorCode=$code closed=$closed" }
                    applied = code == null
                    joining = false
                    if (closed) return@launch
                    if (code != null && code != NEHotspotConfigurationErrorAlreadyAssociated) {
                        eventsChannel.trySend(CameraNetworkEvent.Unavailable)
                        return@launch
                    }
                }
                eventsChannel.trySend(CameraNetworkEvent.Available(Unit))
                nw_path_monitor_start(monitor)
                var host: String? = null
                val networkState = IosCameraNetworkState(ssid)
                var previousMatch: Boolean? = null
                while (isActive && !closed) {
                    paths.tryReceive().let { if (it.isSuccess) host = it.getOrNull() }
                    val currentSsid = currentSsidWhenActive()
                    val matches = currentSsid?.let { it == ssid }
                    if (matches != previousMatch) {
                        log.i { "Wi-Fi iOS expected network matches=$matches gateway=$host" }
                        previousMatch = matches
                    }
                    when (val event = networkState.update(currentSsid, host)) {
                        is CameraNetworkEvent.Ready -> {
                            log.i { "Wi-Fi iOS network ready; camera gateway=${event.host}" }
                            eventsChannel.trySend(event)
                        }

                        is CameraNetworkEvent.Lost -> {
                            log.w { "Wi-Fi iOS network lost; matches=$matches gateway=$host" }
                            eventsChannel.trySend(event)
                            return@launch
                        }

                        else -> Unit
                    }
                    delay(500.milliseconds)
                }
            } catch (cancelled: CancellationException) {
                if (!closed) eventsChannel.trySend(CameraNetworkEvent.Unavailable)
                throw cancelled
            } catch (failure: Exception) {
                log.w { "Wi-Fi iOS network request failed (${failure::class.simpleName})" }
                eventsChannel.trySend(CameraNetworkEvent.Unavailable)
            } finally {
                joining = false
                if (closed && applied) manager.removeConfigurationForSSID(ssid)
            }
        }
    }

    override fun close() {
        if (closed) return
        log.i { "Wi-Fi iOS network request closing; joining=$joining owned=$applied" }
        closed = true
        nw_path_monitor_cancel(monitor)
        nw_path_monitor_set_update_handler(monitor, null)
        paths.close()
        eventsChannel.close()
        // A pending system approval has no cancellation API. Let its completion
        // remove a late join before another request uses the same SSID.
        if (!joining) {
            if (applied) manager.removeConfigurationForSSID(ssid)
            applied = false
            job.cancel()
        }
    }

    companion object {
        private val joinMutex = Mutex()
    }
}

private suspend fun currentSsidWhenActive(): String? {
    val app = UIApplication.sharedApplication
    if (app.applicationState != UIApplicationStateActive) return null
    val current = Channel<String?>(1)
    try {
        NEHotspotNetwork.fetchCurrentWithCompletionHandler { network -> current.trySend(network?.SSID) }
        val ssid = withTimeoutOrNull(2.seconds) { current.receive() }
        return ssid.takeIf { app.applicationState == UIApplicationStateActive }
    } finally {
        current.close()
    }
}

internal class IosCameraNetworkState(private val expectedSsid: String) {
    private var connectedHost: String? = null

    fun update(ssid: String?, host: String?): CameraNetworkEvent<Unit>? {
        val connected = connectedHost
        if (connected == null) {
            if (ssid != expectedSsid || host == null) return null
            connectedHost = host
            return CameraNetworkEvent.Ready(Unit, host)
        }
        // An unavailable SSID is not evidence that the camera network was left.
        if (host != connected || (ssid != null && ssid != expectedSsid)) {
            return CameraNetworkEvent.Lost(Unit)
        }
        return null
    }
}

internal fun iosCameraGateway(candidates: List<String>): String? = candidates.filter { address ->
    val parts = address.split('.')
    parts.size == 4 && parts.all { it.toIntOrNull()?.let { number -> number in 0..255 && number.toString() == it } == true } &&
        parts[0].toInt() in 1..223 && parts[0] != "127" && address != "255.255.255.255"
}.distinct().singleOrNull()
