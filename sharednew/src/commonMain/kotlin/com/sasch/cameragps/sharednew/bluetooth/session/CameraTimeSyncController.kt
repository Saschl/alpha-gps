package com.sasch.cameragps.sharednew.bluetooth.session

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationPacketBuilder
import com.sasch.cameragps.sharednew.bluetooth.coordinator.PlatformTimeZoneInfo
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants as Sony

/** Optional clock setting; camera confirmation must never hold up GPS transmission. */
internal class CameraTimeSyncController(
    private val port: QueuedBleGattPort,
    private val registry: CameraSessionRegistry,
    private val scope: CoroutineScope,
    private val currentTime: () -> CameraTime = CameraTime::current,
) {
    private val log = logging()

    private class Session {
        var syncJob: Job? = null
        var monitorJob: Job? = null
        var supportRead = false
        var supported: Boolean? = null
        var subscribed = false
        var lastOffsets: Pair<Int, Int>? = null
        var pendingResult: CompletableDeferred<CameraTimeSettingResult>? = null
        var lastRefreshSkip: String? = null
    }

    private val sessions = mutableMapOf<String, Session>()

    fun synchronize(identifier: String, onWriteComplete: () -> Unit) {
        val id = identifier.uppercase()
        clear(id)
        val session = Session()
        sessions[id] = session
        log.d { "Time sync[$id]: starting initial synchronization" }
        launchSync(id, session, onWriteComplete)
    }

    fun onDeviceReady(identifier: String) {
        val id = identifier.uppercase()
        val session = sessions[id] ?: return
        if (session.monitorJob != null || session.supported == false) return
        session.monitorJob = scope.launch {
            while (isActive) {
                delay(30.seconds)
                val camera = registry.get(id)
                if (camera == null) {
                    logRefreshSkip(id, session, "camera session missing")
                    continue
                }
                if (!camera.isLocationReady || !port.isConnected(id) || session.syncJob?.isActive == true) {
                    logRefreshSkip(
                        id,
                        session,
                        "ready=${camera.isLocationReady}, connected=${port.isConnected(id)}, syncActive=${session.syncJob?.isActive == true}"
                    )
                    continue
                }
                // CC13 sets both clock and area, so respect either explicit opt-out on refresh.
                if (camera.autoTimeCorrection.enabled == false || camera.autoAreaAdjustment.enabled == false) {
                    logRefreshSkip(
                        id,
                        session,
                        "autoTime=${camera.autoTimeCorrection.enabled}, autoArea=${camera.autoAreaAdjustment.enabled}"
                    )
                    continue
                }
                val offsets = currentTime().offsets
                if (session.supported != false && offsets != session.lastOffsets) {
                    session.lastRefreshSkip = null
                    log.d { "Time sync[$id]: refreshing after offset change ${session.lastOffsets} -> $offsets (standard/DST minutes)" }
                    launchSync(id, session)
                } else {
                    logRefreshSkip(
                        id,
                        session,
                        "offsets unchanged (standard=${offsets.first}min, DST=${offsets.second}min)"
                    )
                }
            }
        }
    }

    fun onResult(identifier: String, value: ByteArray) {
        val id = identifier.uppercase()
        val pending = sessions[id]?.pendingResult
        log.d { "Time sync[$id]: CC0E received=${value.toHexString()}, awaitingResult=${pending?.isActive == true}" }
        if (pending == null) {
            log.d { "Time sync[$id]: ignoring CC0E with no pending attempt" }
            return
        }
        val result = CameraTimeSettingResult.parse(value)
        if (result == null) {
            log.d { "Time sync[$id]: ignoring malformed or unrelated CC0E response" }
            return
        }
        pending.complete(result)
    }

    private fun logRefreshSkip(id: String, session: Session, reason: String) {
        if (session.lastRefreshSkip == reason) return
        session.lastRefreshSkip = reason
        log.d { "Time sync[$id]: refresh skipped: $reason" }
    }

    fun clear(identifier: String) {
        val session = sessions.remove(identifier.uppercase()) ?: return
        session.monitorJob?.cancel()
        session.syncJob?.cancel()
        session.pendingResult?.cancel()
    }

    fun clearAll() {
        sessions.keys.toList().forEach(::clear)
    }

    private fun launchSync(id: String, session: Session, onWriteComplete: () -> Unit = {}) {
        session.syncJob = scope.launch {
            var completed = false
            fun completeWrite() {
                if (!completed && sessions[id] === session && port.isConnected(id)) {
                    completed = true
                    onWriteComplete()
                }
            }
            try {
                sync(id, session, ::completeWrite)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.e(failure) { "Time sync[$id]: failed" }
            } finally {
                session.pendingResult = null
                if (isActive) completeWrite()
            }
        }
    }

    private suspend fun sync(id: String, session: Session, onWriteComplete: () -> Unit) {
        if (!session.supportRead) {
            session.supportRead = true
            if (port.hasCharacteristic(id, Sony.CAMERA_SUPPORT_UUID)) {
                val read = port.execute(id, BleOperation.Read(Sony.CAMERA_SUPPORT_UUID))
                currentCoroutineContext().ensureActive()
                session.supported = (read as? BleOperationResult.Success)?.value
                    ?.let(::parseTimeAreaSupport)
                log.i { "Time sync[$id]: advertised time/area support=${session.supported} (read=$read)" }
                log.d { "Time sync[$id]: CC0D received=${(read as? BleOperationResult.Success)?.value?.toHexString()}" }
            } else {
                log.d { "Time sync[$id]: CC0D missing; checking direct-command characteristic" }
            }
        }
        if (session.supported == false ||
            !port.hasCharacteristic(id, Sony.TIME_SYNC_CHARACTERISTIC_UUID)
        ) {
            log.i { "Time sync[$id]: direct time setting unsupported; location time data remains independent" }
            return
        }

        if (!session.subscribed && port.hasCharacteristic(id, Sony.INITIAL_SETTING_RESULT_UUID)) {
            val subscription =
                port.execute(id, BleOperation.Subscribe(Sony.INITIAL_SETTING_RESULT_UUID, true))
            currentCoroutineContext().ensureActive()
            session.subscribed = subscription is BleOperationResult.Success
            log.d { "Time sync[$id]: CC0E subscription=$subscription" }
            if (!session.subscribed) log.w { "Time sync[$id]: result subscription failed ($subscription)" }
        }

        val time = currentTime()
        session.lastOffsets = time.offsets
        // Arm before writing: cameras can notify before the GATT write callback arrives.
        val pending =
            if (session.subscribed) CompletableDeferred<CameraTimeSettingResult>() else null
        session.pendingResult = pending
        val packet = LocationPacketBuilder.buildTimeSyncPacket(
            time.instant, time.standardOffsetMinutes, time.dstOffsetMinutes,
        )
        log.i { "Time sync[$id]: UTC=${time.instant}, standard=${time.standardOffsetMinutes}min, DST=${time.dstOffsetMinutes}min" }
        log.d { "Time sync[$id]: CC13 sending=${packet.toHexString()}" }
        val write = port.execute(id, BleOperation.Write(Sony.TIME_SYNC_CHARACTERISTIC_UUID, packet))
        currentCoroutineContext().ensureActive()
        log.d { "Time sync[$id]: CC13 write result=$write" }
        // Release the handshake on every write outcome; confirmation waits outside the BLE lane.
        onWriteComplete()
        if (write !is BleOperationResult.Success) {
            log.w { "Time sync[$id]: write failed ($write); camera time was not confirmed" }
            return
        }
        if (pending == null) {
            log.w { "Time sync[$id]: write delivered; camera result unavailable" }
            return
        }
        // Creators' App allows 30 seconds for the camera's application-level result.
        val result = withTimeoutOrNull(30.seconds) { pending.await() }
        when {
            result == null -> log.w { "Time sync[$id]: write delivered; camera result timed out" }
            result.applied -> log.i { "Time sync[$id]: camera applied time/area" }
            else -> log.w { "Time sync[$id]: camera rejected time/area (reason=${result.reason})" }
        }
    }
}

internal data class CameraTime(
    val instant: Instant,
    val standardOffsetMinutes: Int,
    val dstOffsetMinutes: Int,
) {
    val offsets: Pair<Int, Int> get() = standardOffsetMinutes to dstOffsetMinutes

    companion object {
        fun current(): CameraTime {
            val zone = PlatformTimeZoneInfo()
            return CameraTime(Clock.System.now(), zone.standardOffsetMinutes, zone.dstOffsetMinutes)
        }
    }
}

internal data class CameraTimeSettingResult(val applied: Boolean, val reason: UInt) {
    companion object {
        fun parse(value: ByteArray): CameraTimeSettingResult? {
            if (value.size != 8 || value[0] != 7.toByte() ||
                value[1] != 0.toByte() || value[2] != 4.toByte()
            ) return null
            if (value[3] != 0.toByte() && value[3] != 1.toByte()) return null
            val reason = value.drop(4).fold(0u) { accumulated, byte ->
                (accumulated shl 8) or byte.toUByte().toUInt()
            }
            return CameraTimeSettingResult(value[3] == 1.toByte(), reason)
        }
    }
}

/** CC0D is a length byte followed by (big-endian feature type, support byte) triples. */
internal fun parseTimeAreaSupport(value: ByteArray): Boolean? {
    if (value.size < 4 || value[0].toUByte().toInt() != value.size - 1 ||
        (value.size - 1) % 3 != 0
    ) return null
    var supported = false
    for (index in 1 until value.size step 3) {
        val flag = value[index + 2].toInt()
        if (flag !in 0..1) return null
        if (value[index] == 0.toByte() && value[index + 1] == 4.toByte()) supported = flag == 1
    }
    return supported
}
