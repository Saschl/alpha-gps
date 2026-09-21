package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorization
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted

internal object IosBluetoothAuthorization {
    private val log = logging()

    fun logCurrent() {
        val authorization = CBManager.authorization
        log.i {
            "CoreBluetooth authorization: ${describe(authorization)}" +
                    if (authorization == CBManagerAuthorizationAllowedAlways) {
                        " (legacy global grant; the picker can refuse a live central)"
                    } else {
                        ""
                    }
        }
    }

    private fun describe(authorization: CBManagerAuthorization): String = when (authorization) {
        CBManagerAuthorizationNotDetermined -> "notDetermined"
        CBManagerAuthorizationRestricted -> "restricted"
        CBManagerAuthorizationDenied -> "denied"
        CBManagerAuthorizationAllowedAlways -> "allowedAlways"
        else -> "unknown($authorization)"
    }
}
