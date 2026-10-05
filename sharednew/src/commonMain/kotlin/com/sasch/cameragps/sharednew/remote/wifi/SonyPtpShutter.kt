package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

    suspend fun captureStill(): SonyPtpCaptureResult {
        if (!capabilities.deviceInfo.supports(SonyPtpOperation.SDIO_CONTROL_DEVICE) ||
            !capabilities.extendedInfo.supportsControl(SonyPtpControlCode.HALF_PRESS) ||
            !capabilities.extendedInfo.supportsControl(SonyPtpControlCode.FULL_PRESS)) {
            return SonyPtpCaptureResult.Unsupported
        }
        if (captureInProgress) return SonyPtpCaptureResult.Busy
        captureInProgress = true
        return try {
            pressAndRelease()
        } finally {
            captureInProgress = false
        }
    }

    private suspend fun pressAndRelease(): SonyPtpCaptureResult = coroutineScope {
        var halfMayBePressed = false
        var fullMayBePressed = false
        var releaseFailed = false
        var submitted = false
        var captureEvent: Deferred<Boolean>? = null
        try {
            halfMayBePressed = true
            val half = control(SonyPtpControlCode.HALF_PRESS, down = true)
            val halfOutcome = outcome(half)
            if (halfOutcome != SonyPtpCaptureResult.Submitted) return@coroutineScope halfOutcome

            // The confirmed manual-focus probe included this pause; autofocus readiness is still unverified.
            delay(halfPressHoldMs)
            captureEvent = events?.let { monitor ->
                async(start = CoroutineStart.UNDISPATCHED) { monitor.awaitCapture() }
            }
            fullMayBePressed = true
            val full = control(SonyPtpControlCode.FULL_PRESS, down = true)
            val fullOutcome = outcome(full)
            if (fullOutcome != SonyPtpCaptureResult.Submitted) return@coroutineScope fullOutcome
            submitted = true
        } finally {
            withContext(NonCancellable) {
                if (fullMayBePressed) {
                    val release = control(SonyPtpControlCode.FULL_PRESS, down = false)
                    if (release !is PtpIpTransactionResult.Response || release.response.code != 0x2001) {
                        releaseFailed = true
                    }
                }
                if (halfMayBePressed) {
                    val release = control(SonyPtpControlCode.HALF_PRESS, down = false)
                    if (release !is PtpIpTransactionResult.Response || release.response.code != 0x2001) {
                        releaseFailed = true
                    }
                }
            }
            if (!submitted || releaseFailed) captureEvent?.cancel()
        }
        if (releaseFailed) SonyPtpCaptureResult.Uncertain
        else when (captureEvent?.await()) {
            true -> SonyPtpCaptureResult.CaptureEventObserved
            false -> SonyPtpCaptureResult.Uncertain
            null -> SonyPtpCaptureResult.Submitted
        }
    }

    private fun outcome(result: PtpIpTransactionResult): SonyPtpCaptureResult = when (result) {
        is PtpIpTransactionResult.Response -> if (result.response.code == 0x2001)
            SonyPtpCaptureResult.Submitted else SonyPtpCaptureResult.Rejected(result.response.code)
        PtpIpTransactionResult.Uncertain, is PtpIpTransactionResult.Failure,
        PtpIpTransactionResult.Closed -> SonyPtpCaptureResult.Uncertain
    }
}
