package com.sasch.cameragps.sharednew.remote.wifi

internal data class SonyJpegExtent(val width: Int, val height: Int, val byteCount: Int)

/** The camera's image region includes padding after EOI. Embedded thumbnail markers are not EOI. */
internal object SonyJpeg {
    fun inspect(data: ByteArray): SonyJpegExtent {
        fun byte(at: Int) = data[at].toInt() and 0xff
        fun uint16(at: Int) = (byte(at) shl 8) or byte(at + 1)
        require(data.size >= 4 && uint16(0) == 0xffd8) { "Invalid JPEG start" }
        var offset = 2
        var width = 0
        var height = 0
        var inScan = false
        while (offset < data.size) {
            if (inScan) while (offset < data.size && byte(offset) != 0xff) offset++
            if (offset == data.size) break
            require(byte(offset) == 0xff) { "Invalid JPEG segment" }
            while (offset < data.size && byte(offset) == 0xff) offset++
            if (offset == data.size) break
            val marker = byte(offset++)
            if (inScan && (marker == 0 || marker in 0xd0..0xd7)) continue
            if (marker == 0xd9) {
                require(width > 0 && height > 0) { "Missing JPEG dimensions" }
                return SonyJpegExtent(width, height, offset)
            }
            require(marker != 0 && marker != 0xd8 && marker !in 0xd0..0xd7) { "Invalid JPEG marker" }
            if (marker == 1) continue
            require(offset + 2 <= data.size) { "Truncated JPEG length" }
            val length = uint16(offset)
            require(length >= 2 && length <= data.size - offset) { "Invalid JPEG length" }
            if (marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc)) {
                require(length >= 8) { "Invalid JPEG dimensions" }
                height = uint16(offset + 3)
                width = uint16(offset + 5)
                require(width in 1..4096 && height in 1..4096) { "Invalid JPEG dimensions" }
            }
            inScan = marker == 0xda
            offset += length
        }
        throw IllegalArgumentException("Missing JPEG end")
    }
}
