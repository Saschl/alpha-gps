package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidCameraGatewayTest {
    @Test
    fun blankAddressUsesDetectedGateway() {
        for (host in listOf("", "  ")) {
            assertEquals("192.168.122.1", resolveCameraHost(host) { "192.168.122.1" })
        }
    }

    @Test
    fun explicitAddressOverridesGatewayWithoutLookingItUp() {
        assertEquals(
            "192.168.1.2",
            resolveCameraHost(" 192.168.1.2 ") { error("Unexpected lookup") })
    }

    @Test
    fun unavailableGatewayReportsAddressFailure() {
        val error = assertFailsWith<WifiRemoteConnectException> { resolveCameraHost("") { null } }
        assertEquals(WifiRemoteFailure.CameraAddressUnavailable, error.failure)
    }

    @Test
    fun invalidExplicitAddressNeverFallsBackToGateway() {
        for (host in listOf("camera.local", "192.168.1.1:15740", "192.168.1.999", "192.168.01.1")) {
            val error = assertFailsWith<WifiRemoteConnectException> {
                resolveCameraHost(host) { error("Unexpected lookup") }
            }
            assertEquals(WifiRemoteFailure.InvalidAddress, error.failure)
        }
    }

    @Test
    fun detectedAddressMustAlsoBeLiteralIpv4() {
        val error =
            assertFailsWith<WifiRemoteConnectException> { resolveCameraHost("") { "camera.local" } }
        assertEquals(WifiRemoteFailure.InvalidAddress, error.failure)
    }
}
