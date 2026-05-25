package com.sasch.cameragps.sharednew.bluetooth.coordinator

/**
 * Platform abstraction for timezone / DST information that kotlinx-datetime
 * cannot provide (it exposes total offset but not the standard-vs-DST split).
 *
 * Construct a fresh instance each time you need current timezone info —
 * the values are captured at construction time.
 */
expect class PlatformTimeZoneInfo() {
    /** Standard (non-DST) offset from UTC in minutes. */
    val standardOffsetMinutes: Int

    /** Current DST offset in minutes (0 when DST is not active). */
    val dstOffsetMinutes: Int
}

