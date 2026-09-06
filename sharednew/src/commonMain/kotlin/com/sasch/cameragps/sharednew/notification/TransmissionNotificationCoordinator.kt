package com.sasch.cameragps.sharednew.notification

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One status notification, driven by session state rather than individual GPS updates. */
class TransmissionNotificationCoordinator(
    private val scope: CoroutineScope,
    sessions: StateFlow<Map<String, CameraSession>>,
    transmitting: StateFlow<Boolean>,
    // Android's foreground status is mandatory; iOS supplies its opt-in/permission gates.
    enabled: Flow<Boolean> = flowOf(true),
    authorized: Flow<Boolean> = flowOf(true),
    private val publisher: Publisher,
) {
    interface Publisher {
        /** Replace the existing status notification; return after the native operation completes. */
        suspend fun show(cameraCount: Int)

        /** Android keeps a waiting foreground notification; iOS removes its status. */
        fun showIdle()
    }

    val transmittingCameraCount: StateFlow<Int> =
        combine(sessions, transmitting) { current, active ->
            if (active) current.values.count { it.phase == BleSessionPhase.Transmitting } else 0
        }.stateIn(scope, SharingStarted.Eagerly, 0)

    val visibleCameraCount: StateFlow<Int> = combine(
        transmittingCameraCount, enabled, authorized,
    ) { count, isEnabled, isAuthorized ->
        if (isEnabled && isAuthorized) count else 0
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    private var publishingJob: Job? = null

    /** Cancel the returned job before platform teardown to prevent further notification updates. */
    fun start(): Job {
        publishingJob?.let { return it }
        return scope.launch {
            // Finish an in-flight native post before showing idle on disconnect/disable,
            // otherwise that late post can overwrite the waiting state or reappear on iOS.
            visibleCameraCount.collect { count ->
                if (count == 0) publisher.showIdle() else publisher.show(count)
            }
        }.also { publishingJob = it }
    }
}
