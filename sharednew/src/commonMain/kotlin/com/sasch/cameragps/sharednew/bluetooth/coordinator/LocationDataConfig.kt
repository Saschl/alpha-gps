package com.sasch.cameragps.sharednew.bluetooth.coordinator

/**
 * Configuration for location data packets sent to Sony cameras.
 * Shared between Android and iOS — both platforms produce identical byte layouts.
 */
data class LocationDataConfig(
    val shouldSendTimeZoneAndDst: Boolean,
) {
    val dataSize: Int = if (shouldSendTimeZoneAndDst) 95 else 91

    val fixedBytes: ByteArray = byteArrayOf(
        0x00,
        if (shouldSendTimeZoneAndDst) 0x5D else 0x59,
        0x08,
        0x02,
        0xFC.toByte(),
        if (shouldSendTimeZoneAndDst) 0x03 else 0x00,
        0x00,
        0x00,
        0x10,
        0x10,
        0x10,
    )
}

