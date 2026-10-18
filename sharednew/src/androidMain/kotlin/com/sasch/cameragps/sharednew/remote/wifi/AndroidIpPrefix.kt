package com.sasch.cameragps.sharednew.remote.wifi

import android.net.IpPrefix
import android.os.Build
import java.net.InetAddress

internal fun ipPrefixContains(
    prefixAddress: InetAddress,
    prefixLength: Int,
    address: InetAddress,
): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return IpPrefix(prefixAddress, prefixLength).contains(address)
    }

    val prefixBytes = prefixAddress.address
    val addressBytes = address.address
    require(prefixLength in 0..prefixBytes.size * 8) { "Invalid IP prefix length: $prefixLength" }
    if (prefixBytes.size != addressBytes.size) return false

    val fullBytes = prefixLength / 8
    for (index in 0 until fullBytes) {
        if (prefixBytes[index] != addressBytes[index]) return false
    }
    val remainingBits = prefixLength % 8
    if (remainingBits == 0) return true

    val mask = (0xff shl (8 - remainingBits)) and 0xff
    return (prefixBytes[fullBytes].toInt() and mask) == (addressBytes[fullBytes].toInt() and mask)
}
