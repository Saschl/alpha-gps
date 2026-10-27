package com.sasch.cameragps.sharednew.bluetooth.coordinator

import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone

actual class PlatformTimeZoneInfo internal constructor(now: NSDate, tz: NSTimeZone) {
    actual constructor() : this(NSDate(), NSCalendar.currentCalendar.timeZone)

    actual val standardOffsetMinutes: Int
    actual val dstOffsetMinutes: Int

    init {
        val totalOffsetMinutes = tz.secondsFromGMTForDate(now).toInt() / 60
        dstOffsetMinutes = (tz.daylightSavingTimeOffsetForDate(now) / 60.0).toInt()
        standardOffsetMinutes = totalOffsetMinutes - dstOffsetMinutes
    }
}

