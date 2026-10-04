package com.sasch.cameragps.sharednew.remote.wifi

import android.net.IpPrefix
import android.net.LinkProperties
import java.net.Inet4Address

/** The camera is the gateway on its own AP, as in Creators' App's DHCP route lookup. */
internal fun cameraGateway(properties: LinkProperties): String? {
    val local = properties.linkAddresses.filter { it.address is Inet4Address }
    return properties.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway as? Inet4Address }
        .filter { gateway ->
            !gateway.isAnyLocalAddress && !gateway.isLoopbackAddress && !gateway.isMulticastAddress &&
                    local.none { it.address == gateway } && local.any {
                IpPrefix(
                    it.address,
                    it.prefixLength
                ).contains(gateway)
            }
        }
        .mapNotNull { it.hostAddress }.distinct().singleOrNull()
}
