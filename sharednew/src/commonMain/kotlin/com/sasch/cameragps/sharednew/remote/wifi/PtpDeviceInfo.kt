package com.sasch.cameragps.sharednew.remote.wifi

/** The public capability list in the standard PTP DeviceInfo dataset. */
internal data class PtpDeviceInfo(val supportedOperations: Set<Int>) {
    fun supports(code: Int): Boolean = code in supportedOperations
}

internal object SonyPtpOperation {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val SDIO_CONNECT = 0x9201
    const val SDIO_GET_EXT_DEVICE_INFO = 0x9202
    const val SDIO_CONTROL_DEVICE = 0x9207
    const val SDIO_OPEN_SESSION = 0x9210
    const val SDIO_GET_VENDOR_CODE_VERSION = 0x9216
    const val SDIO_GET_DEVICE_DESCRIPTION_FILE = 0x923a
}

/** Parses only through OperationsSupported. Later strings may contain a serial number. */
internal object PtpDeviceInfoParser {
    fun parse(data: ByteArray): PtpDeviceInfo {
        var offset = 0

        fun take(size: Int): Int {
            require(size >= 0 && size <= data.size - offset) { "Malformed PTP DeviceInfo" }
            val start = offset
            offset += size
            return start
        }

        fun uint8(): Int = data[take(1)].toInt() and 0xff
        fun uint32(): Long {
            val start = take(4)
            return (0..3).fold(0L) { value, i ->
                value or ((data[start + i].toLong() and 0xff) shl (8 * i))
            }
        }

        take(8) // PTP version, vendor extension ID and version
        take(uint8() * 2) // PTP string: vendor extension description
        take(2) // functional mode
        val count = uint32()
        require(count <= 4096 && count <= (data.size - offset) / 2) { "Malformed PTP operation count" }
        val operations = buildSet {
            repeat(count.toInt()) {
                val start = take(2)
                add((data[start].toInt() and 0xff) or ((data[start + 1].toInt() and 0xff) shl 8))
            }
        }
        return PtpDeviceInfo(operations)
    }
}
