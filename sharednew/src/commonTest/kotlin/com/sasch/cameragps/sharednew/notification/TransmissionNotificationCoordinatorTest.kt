package com.sasch.cameragps.sharednew.notification

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class TransmissionNotificationCoordinatorTest {
    @Test
    fun noStatusWhileConnectingHandshakingOrWaitingForLocation() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        for (phase in BleSessionPhase.entries) {
            f.sessions.value = mapOf("A" to CameraSession("A", phase))
            runCurrent()
        }
        assertEquals(listOf(0), f.publisher.updates)
        f.transmitting.value = true
        f.sessions.value = mapOf("A" to CameraSession("A", BleSessionPhase.Connecting))
        runCurrent()
        assertEquals(listOf(0), f.publisher.updates)
    }

    @Test
    fun countsOnlyTransmittingCamerasAndRemovesStatusAfterLastDisconnect() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        f.transmitting.value = true
        f.sessions.value = mapOf("A" to ready("A"), "B" to CameraSession("B"))
        runCurrent()
        f.sessions.value = mapOf("A" to ready("A"), "B" to ready("B"))
        runCurrent()
        // Unrelated session updates must not post another notification.
        f.sessions.value = f.sessions.value + ("A" to ready("A").copy(remoteFeatureActive = true))
        runCurrent()
        f.sessions.value = mapOf("B" to ready("B"))
        runCurrent()
        f.sessions.value = emptyMap()
        runCurrent()
        assertEquals(listOf(0, 1, 2, 1, 0), f.publisher.updates)
    }

    @Test
    fun disablingTransmissionPreferenceOrPermissionClearsStatus() = runTest {
        val f = Fixture(backgroundScope)
        runCurrent()
        f.sessions.value = mapOf("A" to ready("A"))
        f.transmitting.value = true
        runCurrent()
        f.enabled.value = false
        runCurrent()
        f.enabled.value = true
        runCurrent()
        f.authorized.value = false
        runCurrent()
        f.authorized.value = true
        runCurrent()
        f.transmitting.value = false
        runCurrent()
        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0), f.publisher.updates)
    }

    @Test
    fun lateNativePostCannotResurrectNotificationAfterDisabling() = runTest {
        val f = Fixture(backgroundScope)
        val posted = CompletableDeferred<Unit>()
        f.publisher.beforeShow = { posted.await() }
        runCurrent()
        f.sessions.value = mapOf("A" to ready("A"))
        f.transmitting.value = true
        runCurrent()
        f.enabled.value = false
        runCurrent()
        assertEquals(0, f.coordinator.visibleCameraCount.value)
        posted.complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 1, 0), f.publisher.updates)
    }

    @Test
    fun foregroundStatusWaitsForLocationAndReturnsToIdleWhenTrackingStops() = runTest {
        val sessions = MutableStateFlow(mapOf("A" to ready("A")))
        val transmitting = MutableStateFlow(false)
        val publisher = FakePublisher()
        // Android supplies no opt-in or notification-permission gate: its service
        // always needs an ongoing notification, including the idle/waiting state.
        TransmissionNotificationCoordinator(
            backgroundScope, sessions, transmitting, publisher = publisher,
        ).start()
        runCurrent()
        assertEquals(listOf(0), publisher.updates)
        transmitting.value = true
        runCurrent()
        transmitting.value = false
        runCurrent()
        sessions.value = emptyMap()
        runCurrent()
        assertEquals(listOf(0, 1, 0), publisher.updates)
    }

    @Test
    fun serviceTeardownDoesNotRepostWaitingAfterPublishingIsCancelled() = runTest {
        val f = Fixture(backgroundScope)
        val publishingJob = f.coordinator.start()
        assertSame(publishingJob, f.coordinator.start())
        runCurrent()
        f.sessions.value = mapOf("A" to ready("A"))
        f.transmitting.value = true
        runCurrent()
        publishingJob.cancel()
        f.transmitting.value = false
        f.sessions.value = emptyMap()
        runCurrent()
        assertEquals(listOf(0, 1), f.publisher.updates)
    }

    private class Fixture(scope: CoroutineScope) {
        val sessions = MutableStateFlow<Map<String, CameraSession>>(emptyMap())
        val transmitting = MutableStateFlow(false)
        val enabled = MutableStateFlow(true)
        val authorized = MutableStateFlow(true)
        val publisher = FakePublisher()
        val coordinator = TransmissionNotificationCoordinator(
            scope,
            sessions,
            transmitting,
            enabled,
            authorized,
            publisher
        )
            .also { it.start() }
    }

    private class FakePublisher : TransmissionNotificationCoordinator.Publisher {
        val updates = mutableListOf<Int>()
        var beforeShow: suspend () -> Unit = {}
        override suspend fun show(cameraCount: Int) {
            beforeShow()
            updates += cameraCount
        }

        override fun showIdle() {
            updates += 0
        }
    }

    private companion object {
        fun ready(id: String) = CameraSession(id, BleSessionPhase.Transmitting)
    }
}
