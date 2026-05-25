package com.sasch.cameragps.sharednew.bluetooth.coordinator

import platform.Foundation.NSCalendar
import platform.Foundation.NSDate

actual class PlatformTimeZoneInfo actual constructor() {
    actual val standardOffsetMinutes: Int
    actual val dstOffsetMinutes: Int

    init {
        val now = NSDate()
        val tz = NSCalendar.currentCalendar.timeZone
        val totalOffsetMinutes = tz.secondsFromGMTForDate(now).toInt() / 60
        dstOffsetMinutes = (tz.daylightSavingTimeOffsetForDate(now) / 60.0).toInt()
        standardOffsetMinutes = totalOffsetMinutes - dstOffsetMinutes
    }
}

