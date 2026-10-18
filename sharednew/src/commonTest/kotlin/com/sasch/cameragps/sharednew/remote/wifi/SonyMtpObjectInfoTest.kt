package com.sasch.cameragps.sharednew.remote.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SonyMtpObjectInfoTest {
    private fun n(value: Long, size: Int) = ByteArray(size) { (value ushr (8 * it)).toByte() }
    private fun text(value: String) = n((value.length + 1).toLong(), 1) +
            value.flatMap { n(it.code.toLong(), 2).toList() }.toByteArray() + n(0, 2)

    private fun prop(code: Long, type: Long, data: ByteArray, handle: Long = 7) =
        n(handle, 4) + n(code, 2) + n(type, 2) + data

    private fun info(
        name: String = "DSC0007.ARW",
        size: Long = 5_000_000_000,
        extras: List<ByteArray> = emptyList()
    ) =
        (listOf(
            prop(0xdc02, 4, n(0xb101, 2)), prop(0xdc04, 8, n(size, 8)),
            prop(0xdc07, 0xffff, text(name))
        ) + extras).let { properties ->
            n(
                properties.size.toLong(),
                4
            ) + properties.fold(byteArrayOf()) { bytes, p -> bytes + p }
        }

    @Test
    fun reads64BitSizeAndPairsOnlyWithStorageAndParentIdentity() {
        val data = info(
            extras = listOf(
                prop(0xdc01, 6, n(2, 4)), prop(0xdc0b, 6, n(42, 4)),
                prop(0xdc08, 0xffff, text("20261018T120000"))
            )
        )
        val parsed = SonyMtpObjectInfo.parse(data, 7)
        assertEquals(5_000_000_000, parsed.size)
        assertEquals("image/x-sony-arw", parsed.mimeType)
        assertEquals("object:2:42:DSC0007:20261018T120000", parsed.captureId)
        assertEquals("object-handle:7", SonyMtpObjectInfo.parse(info(), 7).captureId)
    }

    @Test
    fun skipsUnknownScalarStringAndArrayProperties() {
        val data = info(
            extras = listOf(
                prop(0xdc41, 10, ByteArray(16)),
                prop(0xd801, 0xffff, text("ignored")), prop(0xd802, 0x4004, n(3, 4) + ByteArray(6))
            )
        )
        assertEquals("DSC0007.ARW", SonyMtpObjectInfo.parse(data, 7).filename)
    }

    @Test
    fun rejectsTruncatedOversizedDuplicateWrongHandleAndWrongTypeMetadata() {
        val valid = info()
        val invalid = listOf(
            valid.copyOf(valid.size - 1), valid + byteArrayOf(0), ByteArray(65537),
            info(extras = listOf(prop(0xdc04, 8, n(4, 8)))),
            info(extras = listOf(prop(0xdc01, 6, n(2, 4), handle = 8))),
            info(extras = listOf(prop(0xdc01, 4, n(2, 2)))),
            info(extras = listOf(prop(0xd802, 0x4004, n(0xffffffffL, 4)))),
            n(0xffffffffL, 4), info(size = -1), n(0, 4)
        )
        for (data in invalid) assertFailsWith<IllegalArgumentException> {
            SonyMtpObjectInfo.parse(
                data,
                7
            )
        }
    }

    @Test
    fun rejectsUnsafeAndUnterminatedFilenames() {
        for (name in listOf("../photo.jpg", "dir\\photo.jpg", "", "..", "a\u0000b", "x\n.jpg")) {
            assertFailsWith<IllegalArgumentException> { SonyMtpObjectInfo.parse(info(name), 7) }
        }
        val invalid = info().also { it[it.lastIndex] = 1 }
        assertFailsWith<IllegalArgumentException> { SonyMtpObjectInfo.parse(invalid, 7) }
    }
}
