package com.sasch.cameragps.sharednew.remote.wifi

import android.net.LinkProperties
import java.net.Inet4Address

internal fun resolveCameraHost(host: String, gateway: () -> String?): String {
    val address = host.trim().takeIf { it.isNotEmpty() }
        ?: gateway() ?: throw WifiRemoteConnectException(WifiRemoteFailure.CameraAddressUnavailable)
    try {
        SonyLiveViewEndpoint.fromUrl("http://$address/", address)
    } catch (_: IllegalArgumentException) {
        throw WifiRemoteConnectException(WifiRemoteFailure.InvalidAddress)
    }
    return address
}

/** The camera is the gateway on its own AP, as in Creators' App's DHCP route lookup. */
internal fun cameraGateway(properties: LinkProperties): String? {
    val local = properties.linkAddresses.filter { it.address is Inet4Address }
    return properties.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway as? Inet4Address }
        .filter { gateway ->
            !gateway.isAnyLocalAddress && !gateway.isLoopbackAddress && !gateway.isMulticastAddress &&
                    local.none { it.address == gateway } && local.any {
                ipPrefixContains(it.address, it.prefixLength, gateway)
            }
        }
        .mapNotNull { it.hostAddress }.distinct().singleOrNull()
}
