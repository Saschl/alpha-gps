package com.sasch.cameragps.sharednew.bluetooth.accessory

import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryAuthorization.Authorized
import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryAuthorization.Removed
import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryAuthorization.Unknown
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A wrong `false` here strands the camera until the app restarts. */
class AutoReconnectPolicyTest {

    private fun shouldReconnect(
        pairingRejected: Boolean = false,
        authorization: AccessoryAuthorization = Authorized,
        appEnabled: Boolean = true,
        autoReconnectEnabled: Boolean = true,
        deviceEnabled: Boolean = true,
    ) = AutoReconnectPolicy.shouldReconnect(
        pairingRejected = pairingRejected,
        authorization = authorization,
        appEnabled = appEnabled,
        autoReconnectEnabled = autoReconnectEnabled,
        deviceEnabled = deviceEnabled,
    )

    @Test
    fun savedEnabledAuthorizedCameraReconnects() {
        assertTrue(shouldReconnect(authorization = Authorized))
    }

    // --- The regression this policy exists for -------------------------------
    // Three different ways to reach Unknown; all three used to refuse.

    @Test
    fun cameraThatPredatesAccessorySetupKitStillReconnects() {
        // Never migrated: no accessory record, and never will have one.
        assertTrue(shouldReconnect(authorization = Unknown))
    }

    @Test
    fun cameraReconnectsWhileTheAccessorySessionHasNotActivatedYet() {
        // iOS relaunches us for a BLE event, the camera drops again before
        // `activated` lands. Must not read as de-authorization.
        assertTrue(shouldReconnect(authorization = Unknown))
    }

    @Test
    fun cameraReconnectsAfterTheAccessorySessionWasInvalidated() {
        // The session is never recreated, so a hard gate would refuse every
        // camera for the rest of the process.
        assertTrue(shouldReconnect(authorization = Unknown))
    }

    @Test
    fun unknownAuthorizationStillRespectsTheUsersOwnSwitches() {
        // Failing open is about the accessory record, not the person's toggles.
        assertFalse(shouldReconnect(authorization = Unknown, appEnabled = false))
        assertFalse(shouldReconnect(authorization = Unknown, deviceEnabled = false))
        assertFalse(shouldReconnect(authorization = Unknown, autoReconnectEnabled = false))
    }

    @Test
    fun removedAccessoryIsNotRetried() {
        // Unpaired in Settings: retrying storms a device that cannot answer.
        assertFalse(shouldReconnect(authorization = Removed))
    }

    @Test
    fun removalRefusesEvenWhileEverythingElseInvitesAReconnect() {
        assertFalse(
            shouldReconnect(
                authorization = Removed,
                appEnabled = true,
                autoReconnectEnabled = true,
                deviceEnabled = true,
            ),
        )
    }

    // --- Pre-existing refusals, unchanged ------------------------------------

    @Test
    fun cameraThatRejectedPairingIsNotRetried() {
        assertFalse(shouldReconnect(pairingRejected = true))
    }

    @Test
    fun pairingRefusalWinsOverAnAuthorizedAccessory() {
        assertFalse(shouldReconnect(pairingRejected = true, authorization = Authorized))
    }

    @Test
    fun disabledAppDoesNotReconnect() {
        assertFalse(shouldReconnect(appEnabled = false))
    }

    @Test
    fun unsavedCameraDoesNotReconnect() {
        assertFalse(shouldReconnect(autoReconnectEnabled = false))
    }

    @Test
    fun cameraDisabledInTheDeviceListDoesNotReconnect() {
        assertFalse(shouldReconnect(deviceEnabled = false))
    }
}
