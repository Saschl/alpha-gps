package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosCameraNetworkStateTest {
    private val ssid = "DIRECT-CAMERA"
    private val gateway = "192.168.122.1"

    @Test
    fun unavailableSsidWhileInactiveKeepsTheEstablishedConnection() {
        val state = IosCameraNetworkState(ssid)
        assertEquals(CameraNetworkEvent.Ready(Unit, gateway), state.update(ssid, gateway))
        repeat(120) { assertNull(state.update(null, gateway)) }
        assertNull(state.update(ssid, gateway))
    }

    @Test
    fun initialConnectionStillRequiresTheExpectedSsidAndGateway() {
        val state = IosCameraNetworkState(ssid)
        assertNull(state.update(null, gateway))
        assertNull(state.update("HOME", gateway))
        assertNull(state.update(ssid, null))
        assertEquals(CameraNetworkEvent.Ready(Unit, gateway), state.update(ssid, gateway))
    }

    @Test
    fun confirmedNetworkSwitchDisconnectsEvenWithTheSameGateway() {
        val state = IosCameraNetworkState(ssid)
        state.update(ssid, gateway)
        assertNull(state.update(null, gateway))
        assertEquals(CameraNetworkEvent.Lost(Unit), state.update("HOME", gateway))
    }

    @Test
    fun routeLossOrChangeDisconnectsEvenWhenSsidIsUnavailable() {
        for (host in listOf(null, "192.168.0.1")) {
            val state = IosCameraNetworkState(ssid)
            state.update(ssid, gateway)
            assertEquals(CameraNetworkEvent.Lost(Unit), state.update(null, host))
        }
    }
}
