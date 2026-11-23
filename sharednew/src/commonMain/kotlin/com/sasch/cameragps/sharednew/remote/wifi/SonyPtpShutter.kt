package com.sasch.cameragps.sharednew.remote.wifi

import com.sasch.cameragps.sharednew.remote.BulbExposure
import com.sasch.cameragps.sharednew.remote.BulbPhase
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object SonyPtpControlCode {
    const val HALF_PRESS = 0xd2c1
    const val FULL_PRESS = 0xd2c2
    const val LIVE_VIEW_ENABLE = 0xd313
}

internal sealed interface SonyPtpCaptureResult {
    /** The command was accepted; a capture event is still needed to confirm a photo. */
    data object Submitted : SonyPtpCaptureResult
    data object CaptureEventObserved : SonyPtpCaptureResult
    data object Busy : SonyPtpCaptureResult
    data object StoppedBeforeExposure : SonyPtpCaptureResult
    data object Unsupported : SonyPtpCaptureResult
    data class Rejected(val responseCode: Int) : SonyPtpCaptureResult
    /** A timeout or lost response may have happened after the camera acted. Never retry automatically. */
    data object Uncertain : SonyPtpCaptureResult
}

/** Full press is issued once, followed by releases even after cancellation or a lost response. */
internal class SonyPtpShutter(
    private val commands: PtpIpCommandQueue,
    private val capabilities: SonyPtpInitializationResult.Ready,
    private val events: PtpIpEventMonitor? = null,
    private val halfPressHoldMs: Long = 500,
) {
    init { require(halfPressHoldMs in 0..2_000) }

    private var captureInProgress = false // Called from the owning Main.immediate scope.

    private suspend fun control(code: Int, down: Boolean): PtpIpTransactionResult {
        val params = if ((capabilities.vendorCodeVersion ?: 0) >= 310) listOf(code.toLong(), 1L)
        else listOf(code.toLong())
        return commands.executeDataOut(SonyPtpOperation.SDIO_CONTROL_DEVICE, params,
            byteArrayOf(if (down) 2 else 1, 0), operationTimeoutMs = if (down) 15_000 else 3_000)
    }

    suspend fun captureStill(bulb: BulbExposure? = null): SonyPtpCaptureResult {
        if (!capabilities.deviceInfo.supports(SonyPtpOperation.SDIO_CONTROL_DEVICE) ||
            !capabilities.extendedInfo.supportsControl(SonyPtpControlCode.HALF_PRESS) ||
            !capabilities.extendedInfo.supportsControl(SonyPtpControlCode.FULL_PRESS)) {
            return SonyPtpCaptureResult.Unsupported
        }
        if (captureInProgress) return SonyPtpCaptureResult.Busy
        captureInProgress = true
        return try {
            pressAndRelease(bulb)
        } finally {
            captureInProgress = false
        }
    }

    private suspend fun pressAndRelease(bulb: BulbExposure?): SonyPtpCaptureResult =
        coroutineScope {
        var halfMayBePressed = false
        var fullMayBePressed = false
        var releaseFailed = false
        var submitted = false
        var captureEvent: Deferred<Boolean>? = null
            val attempt = try {
                run attempt@{
                    halfMayBePressed = true
                    val half = control(SonyPtpControlCode.HALF_PRESS, down = true)
                    val halfOutcome = outcome(half)
                    if (halfOutcome != SonyPtpCaptureResult.Submitted) return@attempt halfOutcome

                    // The confirmed manual-focus probe included this pause; autofocus readiness is still unverified.
                    delay(halfPressHoldMs)
                    if (bulb?.stopRequested == true) return@attempt SonyPtpCaptureResult.StoppedBeforeExposure
                    captureEvent = events?.let { monitor ->
                        async(start = CoroutineStart.UNDISPATCHED) {
                            monitor.awaitCapture(if (bulb == null) 20_000 else (bulb.seconds * 2L + 30) * 1000)
                        }
                    }
                    fullMayBePressed = true
                    val full = control(SonyPtpControlCode.FULL_PRESS, down = true)
                    val fullOutcome = outcome(full)
                    if (fullOutcome != SonyPtpCaptureResult.Submitted) return@attempt fullOutcome
                    submitted = true
                    bulb?.hold()
                    SonyPtpCaptureResult.Submitted
                }
        } finally {
            withContext(NonCancellable) {
                if (fullMayBePressed) {
                    val release = try {
                        control(SonyPtpControlCode.FULL_PRESS, down = false)
                    } catch (_: Exception) {
                        null
                    }
                    if (release !is PtpIpTransactionResult.Response || release.response.code != 0x2001) {
                        releaseFailed = true
                    }
                }
                if (halfMayBePressed) {
                    val release = try {
                        control(SonyPtpControlCode.HALF_PRESS, down = false)
                    } catch (_: Exception) {
                        null
                    }
                    if (release !is PtpIpTransactionResult.Response || release.response.code != 0x2001) {
                        releaseFailed = true
                    }
                }
            }
            if (!submitted || releaseFailed) captureEvent?.cancel()
        }
        if (releaseFailed) SonyPtpCaptureResult.Uncertain
        else if (attempt != SonyPtpCaptureResult.Submitted) attempt
        else try {
            bulb?.phase(BulbPhase.Processing)
            val confirmed = if (bulb == null) captureEvent?.await() else
                withTimeoutOrNull(bulb.elapsed + 30.seconds) { captureEvent?.await() }
            when {
                confirmed == true -> SonyPtpCaptureResult.CaptureEventObserved
                captureEvent == null -> SonyPtpCaptureResult.Submitted
                else -> SonyPtpCaptureResult.Uncertain
            }
        } finally {
            captureEvent?.cancel()
        }
    }

    private fun outcome(result: PtpIpTransactionResult): SonyPtpCaptureResult = when (result) {
        is PtpIpTransactionResult.Response -> if (result.response.code == 0x2001)
            SonyPtpCaptureResult.Submitted else SonyPtpCaptureResult.Rejected(result.response.code)
        PtpIpTransactionResult.Uncertain, is PtpIpTransactionResult.Failure,
        PtpIpTransactionResult.Closed -> SonyPtpCaptureResult.Uncertain
    }
}
