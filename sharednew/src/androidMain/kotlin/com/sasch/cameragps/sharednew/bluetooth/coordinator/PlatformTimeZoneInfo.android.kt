package com.sasch.cameragps.sharednew.bluetooth.coordinator

import java.time.Instant
import java.time.ZoneId

actual class PlatformTimeZoneInfo actual constructor() {
    actual val standardOffsetMinutes: Int
    actual val dstOffsetMinutes: Int

    init {
        val tz = ZoneId.systemDefault()
        val now = Instant.now()
        standardOffsetMinutes = tz.rules.getStandardOffset(now).totalSeconds / 60
        dstOffsetMinutes = if (tz.rules.isDaylightSavings(now)) {
            tz.rules.getDaylightSavings(now).toMinutes().toInt()
        } else 0
    }
}

