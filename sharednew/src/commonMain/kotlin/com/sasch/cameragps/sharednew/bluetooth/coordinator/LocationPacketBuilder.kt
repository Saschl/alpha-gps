package com.sasch.cameragps.sharednew.bluetooth.coordinator

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Clock

/**
 * Shared builder for Sony camera BLE location and time-sync packets.
 * Consolidates Android `LocationDataConverter` and iOS `SonyLocationTransmissionUtils`.
 *
 * Accepts platform-neutral inputs (Double coordinates, [PlatformTimeZoneInfo])
 * so callers just extract lat/lng from their platform location type.
 */
object LocationPacketBuilder {

    /**
     * Build the location data packet sent periodically to the camera.
     */
    @Suppress("DEPRECATION")
    fun buildLocationDataPacket(
        config: LocationDataConfig,
        latitude: Double,
        longitude: Double,
        timeZoneInfo: PlatformTimeZoneInfo,
    ): ByteArray {
        val now = Clock.System.now()
        val utc = now.toLocalDateTime(TimeZone.UTC)

        val latE7 = (latitude * 1.0E7).toInt()
        val lngE7 = (longitude * 1.0E7).toInt()
        val locationBytes = intToByteArrayBigEndian(latE7) + intToByteArrayBigEndian(lngE7)

        val dateBytes = byteArrayOf(
            (utc.year.toShort().toInt() shr 8).toByte(),
            utc.year.toShort().toByte(),
            utc.monthNumber.toByte(),
            utc.dayOfMonth.toByte(),
            utc.hour.toByte(),
            utc.minute.toByte(),
            utc.second.toByte(),
        )

        val timeZoneOffsetBytes = shortToByteArray(timeZoneInfo.standardOffsetMinutes.toShort())
        val dstOffsetBytes = shortToByteArray(timeZoneInfo.dstOffsetMinutes.toShort())
        val paddingBytes = ByteArray(65)

        val data = ByteArray(config.dataSize)
        var pos = 0

        config.fixedBytes.copyInto(data, pos); pos += config.fixedBytes.size
        locationBytes.copyInto(data, pos); pos += locationBytes.size
        dateBytes.copyInto(data, pos); pos += dateBytes.size
        paddingBytes.copyInto(data, pos)

        if (config.shouldSendTimeZoneAndDst) {
            pos += paddingBytes.size
            timeZoneOffsetBytes.copyInto(data, pos); pos += timeZoneOffsetBytes.size
            dstOffsetBytes.copyInto(data, pos)
        }

        return data
    }

    /**
     * Build the time-sync packet written to the camera during the handshake.
     */
    @Suppress("DEPRECATION")
    fun buildTimeSyncPacket(timeZoneInfo: PlatformTimeZoneInfo): ByteArray {
        val now = Clock.System.now()
        val local = now.toLocalDateTime(TimeZone.currentSystemDefault())

        val standardOffsetMinutes = timeZoneInfo.standardOffsetMinutes
        val dstMinutes = timeZoneInfo.dstOffsetMinutes
        val hoursComponent = abs(standardOffsetMinutes / 60)
        val offsetMinutesComponent = abs(standardOffsetMinutes % 60)
        val signedOffsetHourByte =
            (if (standardOffsetMinutes < 0) -hoursComponent else hoursComponent).toByte()

        val yearBytes = shortToByteArray(local.year.toShort())

        return ByteArray(13).apply {
            this[0] = 12
            this[1] = 0
            this[2] = 0
            this[3] = yearBytes[0]
            this[4] = yearBytes[1]
            this[5] = local.monthNumber.toByte()
            this[6] = local.dayOfMonth.toByte()
            this[7] = local.hour.toByte()
            this[8] = local.minute.toByte()
            this[9] = local.second.toByte()
            this[10] = if (dstMinutes > 0) 1 else 0
            this[11] = signedOffsetHourByte
            this[12] = offsetMinutesComponent.toByte()
        }
    }

    private fun intToByteArrayBigEndian(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    private fun shortToByteArray(value: Short): ByteArray = byteArrayOf(
        (value.toInt() shr 8).toByte(),
        value.toByte(),
    )
}










