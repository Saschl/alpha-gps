package com.sasch.cameragps.sharednew.remote.wifi

import com.diamondedge.logging.logging

internal class SonyLiveViewEndpointResolver(
    private val commands: PtpIpCommandQueue,
    private val capabilities: SonyPtpInitializationResult.Ready,
) {
    private val log = logging()

    suspend fun resolve(cameraHost: String): SonyLiveViewEndpoint {
        capabilities.deviceDescription?.let { dd ->
            SonyLiveViewEndpoint.findInDeviceDescription(dd, cameraHost)?.let {
                log.i { "Wi-Fi live-view endpoint source=description" }
                return it
            }
        }
        check(capabilities.deviceInfo.supports(GET_PROPERTIES) &&
            capabilities.extendedInfo.supportsProperty(LIVE_VIEW_URL)) { "Camera has no preview endpoint" }
        val parameters = if ((capabilities.vendorCodeVersion ?: 0) >= 310) listOf(0L, 1L) else listOf(0L)
        val result = commands.executeDataIn(GET_PROPERTIES, parameters)
        check(result is PtpIpTransactionResult.Response && result.response.code == 0x2001) {
            "Live-view property read failed"
        }
        val properties = SonyCameraProperties.parse(
            checkNotNull(result.data) { "Missing live-view properties" },
            capabilities.extendedInfo.controlCodes, emptySet(), selectedStrings = setOf(LIVE_VIEW_URL),
        )
        val property = properties[LIVE_VIEW_URL]
        val url = property?.stringValue
        check(property?.enabled == true && !url.isNullOrEmpty()) { "Camera has no available preview URL" }
        return SonyLiveViewEndpoint.fromUrl(url, cameraHost).also {
            log.i { "Wi-Fi live-view endpoint source=property" }
        }
    }

    private companion object {
        const val GET_PROPERTIES = 0x9209
        const val LIVE_VIEW_URL = 0xd278
    }
}
