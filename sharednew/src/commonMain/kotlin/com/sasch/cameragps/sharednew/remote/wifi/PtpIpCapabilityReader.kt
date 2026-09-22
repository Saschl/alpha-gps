package com.sasch.cameragps.sharednew.remote.wifi

internal sealed interface PtpIpCapabilityResult {
    data class Available(val deviceInfo: PtpDeviceInfo) : PtpIpCapabilityResult
    data class Rejected(val responseCode: Int) : PtpIpCapabilityResult
    data object Uncertain : PtpIpCapabilityResult
    data object Failed : PtpIpCapabilityResult
}

/** Standard GetDeviceInfo is safe before Sony's proprietary session negotiation. */
internal class PtpIpCapabilityReader(private val commands: PtpIpCommandQueue) {
    suspend fun read(): PtpIpCapabilityResult = when (
        val result = commands.executeDataIn(SonyPtpOperation.GET_DEVICE_INFO)
    ) {
        is PtpIpTransactionResult.Response -> {
            if (result.response.code != 0x2001) {
                PtpIpCapabilityResult.Rejected(result.response.code)
            } else {
                val data = result.data
                if (data == null) PtpIpCapabilityResult.Failed else {
                    try {
                        PtpIpCapabilityResult.Available(PtpDeviceInfoParser.parse(data))
                    } catch (_: IllegalArgumentException) {
                        PtpIpCapabilityResult.Failed
                    }
                }
            }
        }
        PtpIpTransactionResult.Uncertain -> PtpIpCapabilityResult.Uncertain
        is PtpIpTransactionResult.Failure, PtpIpTransactionResult.Closed -> PtpIpCapabilityResult.Failed
    }
}
