package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidManualWifiSetupTest {
    private val credentials =
        CameraWifiCredentials("DIRECT-camera", "secret-pass", "aa:bb:cc:dd:ee:ff")

    @Test
    fun matchesQuotedSsidAndCaseInsensitiveBssid() {
        assertTrue(matchesCameraWifi(credentials, "\"DIRECT-camera\"", "AA:BB:CC:DD:EE:FF"))
        assertTrue(matchesCameraWifi(credentials, "DIRECT-camera", "aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun rejectsOtherNetworksAndUnavailableIdentity() {
        assertFalse(matchesCameraWifi(credentials, "Home Wi-Fi", "aa:bb:cc:dd:ee:ff"))
        assertFalse(matchesCameraWifi(credentials, "DIRECT-camera", "aa:bb:cc:dd:ee:01"))
        assertFalse(matchesCameraWifi(credentials, "DIRECT-camera", null))
        assertFalse(matchesCameraWifi(credentials, "<unknown ssid>", "aa:bb:cc:dd:ee:ff"))
        assertFalse(matchesCameraWifi(credentials, null, null))
    }

    @Test
    fun camerasWithoutUsableBssidCanStillMatchBySsid() {
        for (bssid in listOf(null, "", "unknown", "00:00:00:00:00:00")) {
            val camera = CameraWifiCredentials("DIRECT-camera", "secret-pass", bssid)
            assertTrue(matchesCameraWifi(camera, "\"DIRECT-camera\"", null))
            assertFalse(matchesCameraWifi(camera, "Home Wi-Fi", null))
        }
    }
}
