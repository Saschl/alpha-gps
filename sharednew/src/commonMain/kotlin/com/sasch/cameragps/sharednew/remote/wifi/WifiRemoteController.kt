package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal interface WifiRemoteConnection {
    val cameraName: String
    val canCapture: Boolean
    val images: Flow<ImageBitmap>
    val lost: Flow<Unit>
    suspend fun capture(): WifiCaptureStatus
    suspend fun close()
}

internal fun interface WifiRemoteConnector {
    suspend fun open(host: String, scope: CoroutineScope): WifiRemoteConnection
}

internal class WifiRemoteConnectException(val failure: WifiRemoteFailure) : Exception("Wi-Fi remote connection failed")

/** App-owned, Main.immediate-confined. Images stay separate from per-camera session state. */
class WifiRemoteController internal constructor(
    private val scope: CoroutineScope,
    private val registry: CameraSessionRegistry,
    private val connector: WifiRemoteConnector,
    private val claimControls: suspend (String) -> Unit,
    private val releaseControls: (String) -> Unit,
) {
    val sessions = registry.sessions
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image = _image.asStateFlow()
    private val _owner = MutableStateFlow<String?>(null)
    val owner = _owner.asStateFlow()
    private var job: Job? = null
    private var captureRequests: Channel<Unit>? = null

    fun connect(identifier: String, host: String) {
        if (job != null) return
        val id = identifier.uppercase()
        if (registry.get(id) == null) registry.upsert(id) { it.copy(phase = BleSessionPhase.Disconnected) }
        _owner.value = id
        _image.value = null
        registry.updateWifiRemote(id, WifiRemoteState(phase = WifiRemotePhase.OpeningSession))
        var entered = false
        val connecting = scope.launch(start = CoroutineStart.LAZY) {
            entered = true
            runSession(id, host.trim())
        }
        job = connecting
        connecting.invokeOnCompletion {
            if (!entered && job === connecting) {
                registry.updateWifiRemote(id, WifiRemoteState())
                _owner.value = null
                job = null
            }
        }
        connecting.start()
    }

    fun capture(identifier: String) {
        val id = identifier.uppercase()
        val state = registry.get(id)?.wifiRemote ?: return
        if (_owner.value != id || state.phase != WifiRemotePhase.Ready || !state.canCaptureStill ||
            state.capture == WifiCaptureStatus.Shooting) return
        registry.updateWifiRemote(id, state.copy(capture = WifiCaptureStatus.Shooting))
        if (captureRequests?.trySend(Unit)?.isSuccess != true) registry.updateWifiRemote(id, state)
    }

    fun disconnect(identifier: String? = null) {
        val id = _owner.value ?: return
        if (identifier != null && !id.equals(identifier, true)) return
        registry.updateWifiRemote(id, WifiRemoteState(phase = WifiRemotePhase.Closing))
        job?.cancel()
    }

    suspend fun closeAndJoin(identifier: String? = null) {
        if (identifier != null && !_owner.value.equals(identifier, true)) return
        val closing = job
        disconnect(identifier)
        closing?.join()
    }

    fun permissionDenied(identifier: String) {
        if (job != null) return
        if (registry.get(identifier) == null) registry.upsert(identifier) { it.copy(phase = BleSessionPhase.Disconnected) }
        registry.updateWifiRemote(identifier, WifiRemoteState(WifiRemotePhase.Failed, WifiRemoteFailure.NetworkPermissionDenied))
    }

    private suspend fun runSession(id: String, host: String) {
        var connection: WifiRemoteConnection? = null
        var failure: WifiRemoteFailure? = null
        try {
            claimControls(id)
            coroutineScope {
                val opened = connector.open(host, this)
                connection = opened
                val requests = Channel<Unit>(Channel.RENDEZVOUS)
                captureRequests = requests
                registry.updateWifiRemote(id, WifiRemoteState(phase = WifiRemotePhase.Ready,
                    canCaptureStill = opened.canCapture, cameraName = opened.cameraName))
                launch {
                    opened.lost.first()
                    throw WifiRemoteConnectException(WifiRemoteFailure.NetworkLost)
                }
                launch {
                    try {
                        opened.images.collect { image ->
                            _image.value = image
                            updateReady(id) { it.copy(hasLiveView = true, preview = WifiPreviewStatus.Streaming) }
                        }
                        updateReady(id) { it.copy(hasLiveView = false, preview = WifiPreviewStatus.Failed) }
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        updateReady(id) { it.copy(hasLiveView = false, preview = WifiPreviewStatus.Failed) }
                    }
                    catch (_: Exception) {
                        updateReady(id) { it.copy(hasLiveView = false, preview = WifiPreviewStatus.Failed) }
                    } finally { _image.value = null }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    for (ignored in requests) {
                        val result = try { opened.capture() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { WifiCaptureStatus.Uncertain }
                        updateReady(id) { it.copy(capture = result) }
                    }
                }
                awaitCancellation()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failed: WifiRemoteConnectException) { failure = failed.failure }
        catch (_: Exception) { failure = WifiRemoteFailure.ProtocolError }
        finally {
            captureRequests?.close()
            captureRequests = null
            withContext(NonCancellable) {
                try { connection?.close() } catch (_: Exception) { /* Best-effort socket teardown. */ }
                finally {
                    _image.value = null
                    registry.updateWifiRemote(id, if (failure == null) WifiRemoteState()
                        else WifiRemoteState(WifiRemotePhase.Failed, failure))
                    releaseControls(id)
                    _owner.value = null
                    job = null
                }
            }
        }
    }

    private fun updateReady(id: String, update: (WifiRemoteState) -> WifiRemoteState) {
        val state = registry.get(id)?.wifiRemote ?: return
        if (state.phase == WifiRemotePhase.Ready) registry.updateWifiRemote(id, update(state))
    }
}
