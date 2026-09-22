package com.sasch.cameragps.sharednew.remote.wifi

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WifiRemoteSessionStateTest {
    @Test
    fun bleDisconnectKeepsWifiAndClosingLastTransportRemovesSession() {
        val sessions = CameraSessionRegistry()
        sessions.upsert("camera") { it.copy(phase = BleSessionPhase.Transmitting, remoteFeatureActive = true) }
        sessions.updateWifiRemote("camera", WifiRemoteState(
            phase = WifiRemotePhase.Ready,
            canCaptureStill = true,
            hasLiveView = true,
        ))

        sessions.markBleDisconnected("camera")
        assertEquals(BleSessionPhase.Disconnected, sessions.get("camera")?.phase)
        assertEquals(WifiRemotePhase.Ready, sessions.get("camera")?.wifiRemote?.phase)
        assertEquals(false, sessions.get("camera")?.remoteFeatureActive)
        assertEquals(emptySet(), sessions.readyIdentifiers())

        sessions.updateWifiRemote("camera", WifiRemoteState())
        assertNull(sessions.get("camera"))
    }

    @Test
    fun bleOnlyDisconnectStillRemovesSession() {
        val sessions = CameraSessionRegistry()
        sessions.upsert("camera")
        sessions.markBleDisconnected("camera")
        assertNull(sessions.get("camera"))
    }
}
