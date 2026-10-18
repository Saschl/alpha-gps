package com.sasch.cameragps.sharednew.remote.wifi

/** Bounded MTP GetObjectPropList metadata for one object; never decodes vendor image arrays. */
internal object SonyMtpObjectInfo {
    fun parse(data: ByteArray, handle: Long): SonyImageInfo {
        require(data.size <= 64 * 1024)
        var offset = 0
        fun take(size: Int): Int {
            require(size >= 0 && size <= data.size - offset) { "Truncated MTP object properties" }
            return offset.also { offset += size }
        }

        fun number(size: Int): Long {
            val start = take(size)
            return (0 until size).fold(0L) { n, i -> n or ((data[start + i].toLong() and 255) shl (8 * i)) }
        }

        fun scalarSize(kind: Int): Int = when (kind) {
            1, 2 -> 1
            3, 4 -> 2
            5, 6 -> 4
            7, 8 -> 8
            9, 10 -> 16
            else -> error("Unsupported MTP property type")
        }

        fun string(): String {
            val count = number(1).toInt()
            if (count == 0) return ""
            val text = buildString { repeat(count - 1) { append(number(2).toInt().toChar()) } }
            require(number(2) == 0L && '\u0000' !in text) { "Malformed MTP string" }
            return text
        }

        val count = number(4)
        require(count in 0..1024) { "Too many MTP object properties" }
        val numbers = mutableMapOf<Int, Long>()
        val strings = mutableMapOf<Int, String>()
        val types = mapOf(
            0xdc01 to 6, 0xdc02 to 4, 0xdc04 to 8, 0xdc07 to 0xffff,
            0xdc08 to 0xffff, 0xdc0b to 6
        )
        val seen = mutableSetOf<Int>()
        repeat(count.toInt()) {
            require(number(4) == handle) { "MTP properties belong to another object" }
            val code = number(2).toInt()
            val kind = number(2).toInt()
            if (code in types) {
                require(seen.add(code) && types[code] == kind) { "Invalid MTP object property" }
                if (kind == 0xffff) strings[code] = string() else numbers[code] =
                    number(scalarSize(kind))
            } else when {
                kind == 0xffff -> string()
                kind in 0x4001..0x400a -> {
                    val length = number(4)
                    require(length in 0..4096)
                    take(length.toInt() * scalarSize(kind - 0x4000))
                }

                else -> take(scalarSize(kind))
            }
        }
        require(offset == data.size) { "Trailing MTP property bytes" }
        val size = requireNotNull(numbers[0xdc04])
        require(size >= 0) { "MTP object size exceeds supported range" }
        val format = requireNotNull(numbers[0xdc02]).toInt()
        val filename = requireNotNull(strings[0xdc07])
        require(
            filename.length in 1..200 && filename != "." && filename != ".." &&
                    filename.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) { "Invalid MTP filename" }
        val capturedAt = strings[0xdc08].orEmpty()
        val storage = numbers[0xdc01]
        val parent = numbers[0xdc0b]
        val captureId = if (storage != null && parent != null) {
            "object:$storage:$parent:${filename.substringBeforeLast('.').uppercase()}:$capturedAt"
        } else "object-handle:$handle"
        val mime = when (format) {
            0x3801 -> "image/jpeg"
            0xb101 -> "image/x-sony-arw"
            0xb110 -> "image/heif"
            else -> ""
        }
        return SonyImageInfo(size, filename, mime, capturedAt, captureId)
    }
}
