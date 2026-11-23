package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.datetime.LocalDate

/** Inclusive calendar dates as recorded by the camera. */
data class PhotoDateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        require(start <= endInclusive)
    }

    private val epochDays = start.toEpochDays()..endInclusive.toEpochDays()

    // Sony encodes local wall time as milliseconds from the epoch, without a timezone.
    internal fun containsLocalMillis(millis: Long): Boolean =
        millis.floorDiv(86_400_000L) in epochDays

    internal fun containsCaptureDate(timestamp: String): Boolean {
        val day = timestamp.substringBefore('T')
        val format = if ('-' in day) LocalDate.Formats.ISO else LocalDate.Formats.ISO_BASIC
        val date = format.parseOrNull(day) ?: return false
        return date in start..endInclusive
    }
}
