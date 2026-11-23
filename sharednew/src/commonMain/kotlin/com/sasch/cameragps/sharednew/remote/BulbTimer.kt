package com.sasch.cameragps.sharednew.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.Duration

internal val bulbDurationSeconds = 1..3600

enum class BulbPhase { Idle, Starting, Exposing, Stopping, Processing, Finished, Uncertain, Failed }

data class BulbTimerState(
    val phase: BulbPhase = BulbPhase.Idle,
    val remainingSeconds: Int = 0,
) {
    val active: Boolean
        get() = phase in setOf(
            BulbPhase.Starting,
            BulbPhase.Exposing,
            BulbPhase.Stopping,
            BulbPhase.Processing
        )
    val canStop: Boolean get() = phase == BulbPhase.Starting || phase == BulbPhase.Exposing
}

interface BulbTimerControls {
    fun startBulbTimer(identifier: String, seconds: Int): Boolean
    fun stopBulbTimer(identifier: String)
}

/** One exposure's deadline and early-stop signal; the transport owns its job and cleanup. */
class BulbExposure(
    val seconds: Int,
    private val publish: (BulbTimerState) -> Unit,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(seconds in bulbDurationSeconds)
    }

    private val stop = CompletableDeferred<Unit>()
    var elapsed: Duration = Duration.ZERO
        private set
    val stopRequested: Boolean get() = stop.isCompleted
    fun stop() {
        stop.complete(Unit)
    }

    fun phase(phase: BulbPhase) {
        publish(BulbTimerState(phase))
    }

    suspend fun hold() = coroutineScope {
        val started = timeSource.markNow()
        val ticker = launch {
            while (true) {
                val remaining = (seconds.seconds - started.elapsedNow()).inWholeMilliseconds
                publish(
                    BulbTimerState(
                        BulbPhase.Exposing,
                        ((remaining + 999) / 1000).toInt().coerceAtLeast(0)
                    )
                )
                delay(200.milliseconds)
            }
        }
        try {
            withTimeoutOrNull(seconds.seconds) { stop.await() }
        } finally {
            elapsed = started.elapsedNow()
            ticker.cancel()
            phase(BulbPhase.Stopping)
        }
    }
}
