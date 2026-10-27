package com.sasch.cameragps.sharednew.bluetooth.coordinator

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneWithName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

class PlatformTimeZoneInfoTest {
    @Test
    fun berlinFallbackUpdatesFoundationOffsetsAndBothSonyPackets() {
        assertBerlinPackets(
            "2026-10-25T00:59:59Z", 60,
            byteArrayOf(12, 0, 0, 7, 0xEA.toByte(), 10, 25, 2, 59, 59, 1, 1, 0),
        )
        assertBerlinPackets(
            "2026-10-25T01:00:00Z", 0,
            byteArrayOf(12, 0, 0, 7, 0xEA.toByte(), 10, 25, 2, 0, 0, 0, 1, 0),
        )
    }

    @Test
    fun berlinSpringForwardUpdatesFoundationOffsetsAndBothSonyPackets() {
        assertBerlinPackets(
            "2026-03-29T00:59:59Z", 0,
            byteArrayOf(12, 0, 0, 7, 0xEA.toByte(), 3, 29, 1, 59, 59, 0, 1, 0),
        )
        assertBerlinPackets(
            "2026-03-29T01:00:00Z", 60,
            byteArrayOf(12, 0, 0, 7, 0xEA.toByte(), 3, 29, 3, 0, 0, 1, 1, 0),
        )
    }

    @Test
    fun foundationSeparatesStandardOffsetFromDstAcrossRegions() {
        val winter = "2026-01-15T12:00:00Z"
        val summer = "2026-07-15T12:00:00Z"
        for ((zone, standard, winterDst, summerDst) in listOf(
            Region("Europe/Berlin", 60, 0, 60),
            Region("Europe/London", 0, 0, 60),
            Region("America/New_York", -300, 0, 60),
            Region("America/St_Johns", -210, 0, 60),
            Region("Australia/Adelaide", 570, 60, 0),
            Region("Australia/Lord_Howe", 630, 30, 0),
            Region("Asia/Kathmandu", 345, 0, 0),
            Region("UTC", 0, 0, 0),
        )) {
            assertOffsets(zone, winter, standard, winterDst)
            assertOffsets(zone, summer, standard, summerDst)
        }
    }

    @Test
    fun lordHoweTransitionPreservesHalfHourDstInLocationPacket() {
        val before = assertOffsets("Australia/Lord_Howe", "2026-04-04T14:59:59Z", 630, 30)
        val after = assertOffsets("Australia/Lord_Howe", "2026-04-04T15:00:00Z", 630, 0)
        assertContentEquals(byteArrayOf(2, 0x76, 0, 30), locationSuffix(before))
        assertContentEquals(byteArrayOf(2, 0x76, 0, 0), locationSuffix(after))
    }

    private fun assertBerlinPackets(utc: String, dst: Int, expectedTime: ByteArray) {
        val info = assertOffsets("Europe/Berlin", utc, 60, dst)
        val timePacket = LocationPacketBuilder.buildTimeSyncPacket(
            Instant.parse(utc), info.standardOffsetMinutes, info.dstOffsetMinutes,
        )
        val suffix = locationSuffix(info)
        assertContentEquals(expectedTime, timePacket)
        assertContentEquals(byteArrayOf(0, 60, 0, dst.toByte()), suffix)
        println("DST_PACKETS utc=$utc cc13=${timePacket.toHexString()} dd11Suffix=${suffix.toHexString()}")
        val basic = LocationPacketBuilder.buildLocationDataPacket(
            LocationDataConfig(shouldSendTimeZoneAndDst = false), 0.0, 0.0, info,
        )
        assertEquals(91, basic.size)
    }

    private fun assertOffsets(
        zone: String,
        utc: String,
        standard: Int,
        dst: Int
    ): PlatformTimeZoneInfo {
        val date = NSDate.dateWithTimeIntervalSince1970(Instant.parse(utc).epochSeconds.toDouble())
        val info = PlatformTimeZoneInfo(date, assertNotNull(NSTimeZone.timeZoneWithName(zone)))
        assertEquals(standard, info.standardOffsetMinutes, "$zone at $utc: standard offset")
        assertEquals(dst, info.dstOffsetMinutes, "$zone at $utc: DST offset")
        println("DST_OFFSETS zone=$zone utc=$utc standard=${info.standardOffsetMinutes} dst=${info.dstOffsetMinutes}")
        return info
    }

    private fun locationSuffix(info: PlatformTimeZoneInfo): ByteArray {
        val packet = LocationPacketBuilder.buildLocationDataPacket(
            LocationDataConfig(shouldSendTimeZoneAndDst = true), 0.0, 0.0, info,
        )
        assertEquals(95, packet.size)
        return packet.copyOfRange(91, 95)
    }

    private data class Region(
        val zone: String,
        val standard: Int,
        val winterDst: Int,
        val summerDst: Int,
    )
}
