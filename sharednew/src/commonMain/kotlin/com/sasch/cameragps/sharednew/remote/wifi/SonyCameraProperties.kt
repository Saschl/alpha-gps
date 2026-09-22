package com.sasch.cameragps.sharednew.remote.wifi

/** Unselected strings and arrays are consumed without decoding. */
internal data class SonyScalarProperty(val enabled: Boolean, val value: Long?)

internal object SonyCameraProperties {
    fun parse(
        data: ByteArray,
        controlCodes: Set<Int>,
        selected: Set<Int>
    ): Map<Int, SonyScalarProperty> {
        var offset = 0
        fun take(size: Int): Int {
            require(size >= 0 && size <= data.size - offset) { "Truncated camera properties" }
            return offset.also { offset += size }
        }

        fun number(size: Int): Long {
            val start = take(size)
            return (0 until size).fold(0L) { value, i -> value or ((data[start + i].toLong() and 255) shl (8 * i)) }
        }

        fun scalarSize(kind: Int) = when (kind) {
            1, 2 -> 1
            3, 4 -> 2
            5, 6 -> 4
            7, 8 -> 8
            9, 10 -> 16
            else -> error("Unsupported camera property type")
        }

        fun value(kind: Int): Long? = when {
            kind == 0xffff -> {
                take(number(1).toInt() * 2); null
            }

            kind in 0x4001..0x400a -> {
                val count = number(4)
                require(count in 0..4096) { "Camera property array too large" }
                take(count.toInt() * scalarSize(kind - 0x4000)); null
            }

            else -> scalarSize(kind).let { size ->
                if (size <= 8) number(size) else {
                    take(size); null
                }
            }
        }

        val count = number(8)
        require(count in 0..4096) { "Too many camera properties" }
        val properties = mutableMapOf<Int, SonyScalarProperty>()
        val seen = mutableSetOf<Int>()
        repeat(count.toInt()) {
            val code = number(2).toInt()
            val kind = number(2).toInt()
            number(1) // Access flag; availability properties are read-only.
            val enabled = number(1)
            if (code in controlCodes) {
                take(4)
                require(number(1) == 0L) { "Unsupported camera control form" }
            } else {
                value(kind)
                val current = value(kind)
                when (number(1).toInt()) {
                    0 -> Unit
                    1 -> repeat(3) { value(kind) }
                    2 -> repeat(2) {
                        val options = number(2).toInt()
                        require(options <= 4096) { "Too many camera property values" }
                        repeat(options) { value(kind) }
                    }

                    else -> error("Unsupported camera property form")
                }
                if (code in selected) {
                    require(seen.add(code)) { "Duplicate camera property" }
                    properties[code] = SonyScalarProperty(enabled in 1L..2L, current)
                }
            }
        }
        require(offset == data.size) { "Trailing camera property bytes" }
        return properties
    }
}
