package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PtpDeviceInfoTest {
    @Test
    fun readsSonyOperationCodesWithoutParsingPrivateFields() {
        val bytes = byteArrayOf(
            100, 0, 6, 0, 0, 0, 1, 0, // PTP and vendor versions
            1, 0, 0, // empty extension description
            0, 0, // functional mode
            3, 0, 0, 0, // operation count
            1, 16, 1, 0x92.toByte(), 0x3a, 0x92.toByte(),
            0xff.toByte(), 0xff.toByte(), // ignored private trailing fields
        )
        val info = PtpDeviceInfoParser.parse(bytes)
        assertEquals(3, info.supportedOperations.size)
        assertTrue(info.supports(SonyPtpOperation.GET_DEVICE_INFO))
        assertTrue(info.supports(SonyPtpOperation.SDIO_CONNECT))
        assertTrue(info.supports(SonyPtpOperation.SDIO_GET_DEVICE_DESCRIPTION_FILE))
        assertFalse(info.supports(SonyPtpOperation.SDIO_CONTROL_DEVICE))
    }

    @Test
    fun rejectsTruncatedAndOversizedOperationLists() {
        assertFailsWith<IllegalArgumentException> { PtpDeviceInfoParser.parse(ByteArray(8)) }
        val prefix = byteArrayOf(100, 0, 6, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0)
        assertFailsWith<IllegalArgumentException> {
            PtpDeviceInfoParser.parse(prefix + byteArrayOf(0xff.toByte(), 0xff.toByte(), 0, 0))
        }
    }
}
