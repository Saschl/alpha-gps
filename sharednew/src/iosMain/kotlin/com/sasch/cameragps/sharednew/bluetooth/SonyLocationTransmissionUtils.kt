package com.sasch.cameragps.sharednew.bluetooth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSTimeZone
import platform.Foundation.timeZoneForSecondsFromGMT
import kotlin.math.abs

internal data class SonyLocationTransmissionConfig(
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

internal object SonyLocationTransmissionUtils {
    fun hasTimeZoneDstFlag(value: ByteArray): Boolean {
        return value.size >= 5 && (value[4].toInt() and 0x02) != 0
    }

    fun buildLocationDataPacket(
        config: SonyLocationTransmissionConfig,
        location: CLLocation,
    ): ByteArray {
        val locationBytes = convertCoordinates(location)
        val dateBytes = convertUtcDate()
        val timeZoneOffsetBytes = convertTimeZoneOffset()
        val dstOffsetBytes = convertDstOffset()
        val paddingBytes = ByteArray(65)

        val data = ByteArray(config.dataSize)
        var currentPosition = 0

        config.fixedBytes.copyInto(data, destinationOffset = currentPosition)
        currentPosition += config.fixedBytes.size

        locationBytes.copyInto(data, destinationOffset = currentPosition)
        currentPosition += locationBytes.size

        dateBytes.copyInto(data, destinationOffset = currentPosition)
        currentPosition += dateBytes.size

        paddingBytes.copyInto(data, destinationOffset = currentPosition)

        if (config.shouldSendTimeZoneAndDst) {
            currentPosition += paddingBytes.size
            timeZoneOffsetBytes.copyInto(data, destinationOffset = currentPosition)
            currentPosition += timeZoneOffsetBytes.size
            dstOffsetBytes.copyInto(data, destinationOffset = currentPosition)
        }

        return data
    }

    fun buildTimeSyncPacket(): ByteArray {
        val now = platform.Foundation.NSDate()
        val calendar = NSCalendar.currentCalendar
        val components = calendar.components(
            unitFlags = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond,
            fromDate = now,
        )

        val timeZone = NSCalendar.currentCalendar.timeZone
        val totalOffsetMinutes = timeZone.secondsFromGMTForDate(now).toInt() / 60
        val dstMinutes = (timeZone.daylightSavingTimeOffsetForDate(now) / 60.0).toInt()
        val standardOffsetMinutes = totalOffsetMinutes - dstMinutes
        val hoursComponent = abs(standardOffsetMinutes / 60)
        val offsetMinutesComponent = abs(standardOffsetMinutes % 60)
        val signedOffsetHourByte =
            (if (standardOffsetMinutes < 0) -hoursComponent else hoursComponent).toByte()
        val yearBytes = components.year.toShort().toByteArray()

        return ByteArray(13).apply {
            this[0] = 12
            this[1] = 0
            this[2] = 0
            this[3] = yearBytes[0]
            this[4] = yearBytes[1]
            this[5] = components.month.toByte()
            this[6] = components.day.toByte()
            this[7] = components.hour.toByte()
            this[8] = components.minute.toByte()
            this[9] = components.second.toByte()
            this[10] = if (dstMinutes > 0) 1 else 0
            this[11] = signedOffsetHourByte
            this[12] = offsetMinutesComponent.toByte()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun convertCoordinates(location: CLLocation): ByteArray {
        val latitude = (location.coordinate.useContents { latitude } * 1.0E7).toInt()
        val longitude = (location.coordinate.useContents { longitude } * 1.0E7).toInt()
        return latitude.toByteArray() + longitude.toByteArray()
    }

    private fun convertUtcDate(): ByteArray {
        val now = platform.Foundation.NSDate()
        val calendar = NSCalendar.currentCalendar

        calendar.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        val components = calendar.components(
            unitFlags = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond,
            fromDate = now,
        )

        val yearBytes = components.year.toShort().toByteArray()
        return byteArrayOf(
            yearBytes[0],
            yearBytes[1],
            components.month.toByte(),
            components.day.toByte(),
            components.hour.toByte(),
            components.minute.toByte(),
            components.second.toByte(),
        )
    }

    private fun convertTimeZoneOffset(): ByteArray {
        val now = platform.Foundation.NSDate()
        val timeZone = NSCalendar.currentCalendar.timeZone
        val currentOffsetMinutes = timeZone.secondsFromGMTForDate(now).toInt() / 60
        val dstOffsetMinutes = (timeZone.daylightSavingTimeOffsetForDate(now) / 60.0).toInt()
        val standardOffsetMinutes = currentOffsetMinutes - dstOffsetMinutes
        return standardOffsetMinutes.toShort().toByteArray()
    }

    private fun convertDstOffset(): ByteArray {
        val now = platform.Foundation.NSDate()
        val dstMinutes =
            (NSCalendar.currentCalendar.timeZone.daylightSavingTimeOffsetForDate(now) / 60.0).toInt()
        return dstMinutes.toShort().toByteArray()
    }
}

private fun Int.toByteArray(): ByteArray = byteArrayOf(
    (this shr 24).toByte(),
    (this shr 16).toByte(),
    (this shr 8).toByte(),
    this.toByte(),
)

private fun Short.toByteArray(): ByteArray = byteArrayOf(
    (toInt() shr 8).toByte(),
    toByte(),
)



