package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Uses the active PTP/IP session; call after preview/shutter cleanup, before closing sockets. */
internal class SonyWifiShutdown(
    private val commands: PtpIpCommandQueue,
    private val capabilities: SonyPtpInitializationResult.Ready,
) {
    suspend fun request(): WifiShutdownStatus {
        if (!capabilities.deviceInfo.supports(GET_PROPERTIES) ||
            !capabilities.deviceInfo.supports(SonyPtpOperation.SDIO_CONTROL_DEVICE) ||
            controls.none { (control, property) ->
                capabilities.extendedInfo.supportsControl(control) &&
                        capabilities.extendedInfo.supportsProperty(property)
            }
        ) return WifiShutdownStatus.Unavailable
        return try {
            withTimeoutOrNull(7_000) {
                val flags =
                    if ((capabilities.vendorCodeVersion ?: 0) >= 310) listOf(1L) else emptyList()
                val response = commands.executeDataIn(
                    GET_PROPERTIES,
                    listOf(0L) + flags,
                    operationTimeoutMs = 3_000
                )
                if (response !is PtpIpTransactionResult.Response || response.response.code != 0x2001 || response.data == null) {
                    return@withTimeoutOrNull WifiShutdownStatus.Unconfirmed
                }
                val allowed = SonyWifiShutdownProperties.parse(
                    response.data,
                    capabilities.extendedInfo.controlCodes
                )
                val control = controls.firstOrNull { (code, property) ->
                    capabilities.extendedInfo.supportsControl(code) &&
                            capabilities.extendedInfo.supportsProperty(property) && property in allowed
                }?.first ?: return@withTimeoutOrNull WifiShutdownStatus.Unavailable
                val parameters = listOf(control.toLong()) + flags
                val down = commands.executeDataOut(
                    SonyPtpOperation.SDIO_CONTROL_DEVICE, parameters,
                    byteArrayOf(2, 0), operationTimeoutMs = 2_000
                )
                if (!accepted(down)) return@withTimeoutOrNull WifiShutdownStatus.Unconfirmed
                val up = commands.executeDataOut(
                    SonyPtpOperation.SDIO_CONTROL_DEVICE, parameters,
                    byteArrayOf(1, 0), operationTimeoutMs = 2_000
                )
                if (accepted(up)) WifiShutdownStatus.Requested else WifiShutdownStatus.Unconfirmed
            } ?: WifiShutdownStatus.Unconfirmed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WifiShutdownStatus.Unconfirmed
        }
    }

    private fun accepted(result: PtpIpTransactionResult) =
        result is PtpIpTransactionResult.Response && result.response.code == 0x2001

    private companion object {
        const val GET_PROPERTIES = 0x9209

        // Creators' App prefers ending the direct connection; power-off is its fallback.
        val controls = listOf(0xd308 to 0xd12b, 0xd2e8 to 0xd296)
    }
}

/** Retains only Wi-Fi shutdown availability; unused strings and arrays are consumed without decoding. */
internal object SonyWifiShutdownProperties {
    fun parse(data: ByteArray, controlCodes: Set<Int>): Set<Int> {
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
        val allowed = mutableSetOf<Int>()
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
                if (code == 0xd12b || code == 0xd296) {
                    require(seen.add(code)) { "Duplicate camera Wi-Fi property" }
                    if (enabled in 1L..2L && current != null && (current and 0xffff) == 1L) allowed += code
                }
            }
        }
        require(offset == data.size) { "Trailing camera property bytes" }
        return allowed
    }
}
