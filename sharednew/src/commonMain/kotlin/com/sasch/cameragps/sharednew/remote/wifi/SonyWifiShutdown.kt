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

internal object SonyWifiShutdownProperties {
    fun parse(data: ByteArray, controlCodes: Set<Int>): Set<Int> =
        SonyCameraProperties.parse(data, controlCodes, setOf(0xd12b, 0xd296))
            .filterValues { it.enabled && it.value?.and(0xffff) == 1L }.keys
}
