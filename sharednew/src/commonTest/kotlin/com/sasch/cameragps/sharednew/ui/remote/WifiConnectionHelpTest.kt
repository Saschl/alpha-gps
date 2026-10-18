package com.sasch.cameragps.sharednew.ui.remote

import com.sasch.cameragps.sharednew.remote.wifi.WifiRemoteFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WifiConnectionHelpTest {
    @Test
    fun onlySocketSetupAndPermissionFailuresAutomaticallyPrompt() {
        val automatic = setOf(
            WifiRemoteFailure.NetworkConnectionFailed,
            WifiRemoteFailure.NetworkPermissionDenied
        )
        assertEquals(automatic, WifiRemoteFailure.entries.filter {
            it.connectionHelpMode() == WifiConnectionHelpMode.Automatic
        }.toSet())
    }

    @Test
    fun ambiguousConnectionFailuresOfferHelpOnRequest() {
        for (failure in listOf(
            WifiRemoteFailure.CameraUnavailable, WifiRemoteFailure.ProtocolError,
            WifiRemoteFailure.NetworkJoinFailed, WifiRemoteFailure.NetworkLost
        )) {
            assertEquals(WifiConnectionHelpMode.OnRequest, failure.connectionHelpMode())
        }
    }

    @Test
    fun healthyConnectionAndSpecificSetupFailuresDoNotOfferUnrelatedNetworkHelp() {
        assertNull(null.connectionHelpMode())
        for (failure in listOf(
            WifiRemoteFailure.InvalidAddress, WifiRemoteFailure.CameraRefused,
            WifiRemoteFailure.BluetoothRequired, WifiRemoteFailure.WifiDisabled,
            WifiRemoteFailure.LocationServicesRequired
        )) {
            assertNull(failure.connectionHelpMode())
        }
    }
}
