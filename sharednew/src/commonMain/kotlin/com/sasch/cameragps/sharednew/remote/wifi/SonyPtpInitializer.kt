package com.sasch.cameragps.sharednew.remote.wifi

import com.diamondedge.logging.logging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal data class SonyExtendedDeviceInfo(
    val version: Int,
    val propertyCodes: Set<Int>,
    val controlCodes: Set<Int>,
) {
    fun supportsControl(code: Int): Boolean = code in controlCodes
    fun supportsProperty(code: Int): Boolean = code in propertyCodes
}

internal object SonyExtendedDeviceInfoParser {
    fun parse(data: ByteArray): SonyExtendedDeviceInfo {
        var offset = 0
        fun take(size: Int): Int {
            require(size >= 0 && size <= data.size - offset) { "Malformed Sony extended device info" }
            return offset.also { offset += size }
        }
        fun uint16(): Int {
            val start = take(2)
            return (data[start].toInt() and 0xff) or ((data[start + 1].toInt() and 0xff) shl 8)
        }
        fun uint32(): Long {
            val start = take(4)
            return (0..3).fold(0L) { value, index ->
                value or ((data[start + index].toLong() and 0xff) shl (8 * index))
            }
        }
        fun codes(): Set<Int> {
            val count = uint32()
            require(count <= 4096 && count <= (data.size - offset) / 2) {
                "Malformed Sony extended device info count"
            }
            return buildSet { repeat(count.toInt()) { add(uint16()) } }
        }
        val version = uint16()
        val properties = codes()
        val controls = codes()
        return SonyExtendedDeviceInfo(version, properties, controls)
    }
}

internal object SonyDidVersion {
    fun supportsRemoteWithTransfer(version: String?): Boolean =
        version?.substringBefore('.')?.toIntOrNull()?.let { it >= 4 } ?: false

    fun supportsSeparateTransfer(
        version: String?,
        mediaServer: Boolean,
        deviceInfo: PtpDeviceInfo
    ): Boolean =
        !supportsRemoteWithTransfer(version) && mediaServer &&
                (version?.substringBefore('.')?.toIntOrNull() ?: 0) >= 3 &&
                deviceInfo.supports(SonyPtpOperation.SDIO_OPEN_SESSION)

    private val serverVersionTag = Regex(
        """<((?:[A-Za-z_][\w.-]*:)?X_ServerVersion)(?:\s+[^<>]*)?>([^<]*)</\1\s*>"""
    )

    fun parse(did: ByteArray): String {
        require(did.size <= 64 * 1024) { "DID XML is too large" }
        val xml = did.decodeToString().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        require(!xml.contains("<!")) { "Unsupported DID XML declaration" }
        val matches = serverVersionTag.findAll(xml).toList()
        require(matches.size == 1) { "DID XML has no unambiguous server version" }
        val version = matches.single().groupValues[2].trim()
        require(version.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) { "Invalid DID server version" }
        require(version.substringBefore('.').toIntOrNull() != null) { "Invalid DID server version" }
        return version
    }
}

internal object SonyDidMediaServer {
    private val tag = Regex(
        """<((?:[A-Za-z_][\w.-]*:)?X_PTP_MediaServerSupport)(?:\s+[^<>]*)?>([^<]*)</\1\s*>"""
    )

    fun supported(did: ByteArray): Boolean {
        require(did.size <= 64 * 1024) { "DID XML is too large" }
        val xml = did.decodeToString(throwOnInvalidSequence = true)
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        require(!xml.contains("<!")) { "Unsupported DID XML declaration" }
        val matches = tag.findAll(xml).toList()
        require(matches.size <= 1) { "Ambiguous PTP media-server support" }
        return matches.singleOrNull()?.groupValues?.get(2)?.trim() == "Enable"
    }
}

internal enum class SonySessionPurpose { RemoteShooting, ImageTransfer }

internal sealed interface SonyPtpInitializationResult {
    data class Ready(
        val serverVersion: String?,
        val vendorCodeVersion: Long?,
        val extendedInfo: SonyExtendedDeviceInfo,
        val deviceInfo: PtpDeviceInfo,
        /** Held in memory only for later live-view endpoint discovery. */
        val deviceDescription: ByteArray?,
        val mediaServerSupported: Boolean = false,
        val purpose: SonySessionPurpose = SonySessionPurpose.RemoteShooting,
    ) : SonyPtpInitializationResult {
        val isRemoteWithTransfer: Boolean get() = SonyDidVersion.supportsRemoteWithTransfer(serverVersion)
        val supportsSeparateTransferSession: Boolean
            get() =
                SonyDidVersion.supportsSeparateTransfer(
                    serverVersion,
                    mediaServerSupported,
                    deviceInfo
                )

        override fun toString() =
            "SonyPtpReady(serverVersion=$serverVersion, vendorCodeVersion=$vendorCodeVersion, " +
                    "mediaServerSupported=$mediaServerSupported, purpose=$purpose)"
    }

    data class Rejected(val stage: String, val responseCode: Int) : SonyPtpInitializationResult
    data class Uncertain(val stage: String) : SonyPtpInitializationResult
    data class Failed(val stage: String) : SonyPtpInitializationResult
}

/** Sony's observed DD/DID → session → three-connect handshake on the a6700. */
internal class SonyPtpInitializer(private val commands: PtpIpCommandQueue) {
    private val log = logging()
    private var sessionOpen = false
    private var initializationStarted = false

    private class Rejection(val stage: String, val code: Int) : Exception()
    private class Uncertain(val stage: String) : Exception()
    private class Failure(val stage: String) : Exception()

    private suspend fun operation(
        stage: String,
        code: Int,
        parameters: List<Long> = emptyList(),
        dataIn: Boolean = false,
    ): PtpIpTransactionResult.Response {
        log.i { "Wi-Fi Sony stage=$stage request=0x${code.toString(16)}" }
        val result = if (dataIn) commands.executeDataIn(code, parameters)
        else commands.executeNoData(code, parameters)
        log.i { "Wi-Fi Sony stage=$stage ${result.diagnosticSummary()}" }
        return when (result) {
            is PtpIpTransactionResult.Response -> {
                if (result.response.code != 0x2001) throw Rejection(stage, result.response.code)
                result
            }
            PtpIpTransactionResult.Uncertain -> throw Uncertain(stage)
            is PtpIpTransactionResult.Failure, PtpIpTransactionResult.Closed -> throw Failure(stage)
        }
    }

    suspend fun initialize(purpose: SonySessionPurpose = SonySessionPurpose.RemoteShooting): SonyPtpInitializationResult {
        if (initializationStarted) return SonyPtpInitializationResult.Failed("already_initialized")
        initializationStarted = true
        var ready = false
        try {
            log.i { "Wi-Fi Sony stage=get_device_info starting" }
            val deviceInfo = when (val result = PtpIpCapabilityReader(commands).read()) {
                is PtpIpCapabilityResult.Available -> result.deviceInfo
                is PtpIpCapabilityResult.Rejected -> throw Rejection("get_device_info", result.responseCode)
                PtpIpCapabilityResult.Uncertain -> throw Uncertain("get_device_info")
                PtpIpCapabilityResult.Failed -> throw Failure("get_device_info")
            }
            log.i { "Wi-Fi Sony stage=get_device_info complete" }
            if (!deviceInfo.supports(SonyPtpOperation.SDIO_CONNECT) ||
                !deviceInfo.supports(SonyPtpOperation.SDIO_GET_EXT_DEVICE_INFO)) {
                throw Failure("unsupported_camera")
            }

            var description: ByteArray? = null
            var serverVersion: String? = null
            var mediaServerSupported = false
            if (deviceInfo.supports(SonyPtpOperation.SDIO_GET_DEVICE_DESCRIPTION_FILE)) {
                description = operation("get_dd_xml", SonyPtpOperation.SDIO_GET_DEVICE_DESCRIPTION_FILE,
                    listOf(1), dataIn = true).data ?: throw Failure("get_dd_xml")
                require(description.size <= 64 * 1024) { "DD XML is too large" }
                val did = operation("get_did_xml", SonyPtpOperation.SDIO_GET_DEVICE_DESCRIPTION_FILE,
                    listOf(2), dataIn = true).data ?: throw Failure("get_did_xml")
                serverVersion = try { SonyDidVersion.parse(did) } catch (_: IllegalArgumentException) {
                    throw Failure("get_did_xml")
                }
                mediaServerSupported = SonyDidMediaServer.supported(did)
            }
            val remoteWithTransfer = SonyDidVersion.supportsRemoteWithTransfer(serverVersion)
            val separateTransfer = SonyDidVersion.supportsSeparateTransfer(
                serverVersion,
                mediaServerSupported,
                deviceInfo
            )
            if (purpose == SonySessionPurpose.ImageTransfer && !separateTransfer) {
                throw Failure("unsupported_transfer_session")
            }
            if (remoteWithTransfer || separateTransfer) {
                if (!deviceInfo.supports(SonyPtpOperation.SDIO_OPEN_SESSION)) throw Failure("unsupported_session")
                val mode = when {
                    remoteWithTransfer -> 2L
                    purpose == SonySessionPurpose.ImageTransfer -> 1L
                    else -> 0L
                }
                operation("open_session", SonyPtpOperation.SDIO_OPEN_SESSION, listOf(1, mode))
            } else {
                operation("open_session", SonyPtpOperation.OPEN_SESSION, listOf(1))
            }
            sessionOpen = true

            operation("sdio_connect_1", SonyPtpOperation.SDIO_CONNECT, listOf(1, 0, 0), dataIn = true)
            operation("sdio_connect_2", SonyPtpOperation.SDIO_CONNECT, listOf(2, 0, 0), dataIn = true)
            val vendorVersion = if (deviceInfo.supports(SonyPtpOperation.SDIO_GET_VENDOR_CODE_VERSION)) {
                operation("get_vendor_code_version", SonyPtpOperation.SDIO_GET_VENDOR_CODE_VERSION)
                    .response.parameters.firstOrNull()
            } else null
            val extendedParameters = if (vendorVersion != null && vendorVersion >= 310) listOf(300L, 1L)
            else listOf(300L)
            val extendedData = operation("get_extended_device_info", SonyPtpOperation.SDIO_GET_EXT_DEVICE_INFO,
                extendedParameters, dataIn = true).data ?: throw Failure("get_extended_device_info")
            val extendedInfo = try { SonyExtendedDeviceInfoParser.parse(extendedData) }
            catch (_: IllegalArgumentException) { throw Failure("get_extended_device_info") }
            operation("sdio_connect_3", SonyPtpOperation.SDIO_CONNECT, listOf(3, 0, 0), dataIn = true)
            // Transfer operations can appear only after entering the new session mode.
            val activeDeviceInfo = if (purpose == SonySessionPurpose.ImageTransfer) {
                when (val result = PtpIpCapabilityReader(commands).read()) {
                    is PtpIpCapabilityResult.Available -> result.deviceInfo
                    is PtpIpCapabilityResult.Rejected -> throw Rejection(
                        "transfer_device_info",
                        result.responseCode
                    )

                    PtpIpCapabilityResult.Uncertain -> throw Uncertain("transfer_device_info")
                    PtpIpCapabilityResult.Failed -> throw Failure("transfer_device_info")
                }
            } else deviceInfo
            ready = true
            return SonyPtpInitializationResult.Ready(
                serverVersion,
                vendorVersion,
                extendedInfo,
                activeDeviceInfo,
                description,
                mediaServerSupported,
                purpose
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: Rejection) {
            return SonyPtpInitializationResult.Rejected(rejected.stage, rejected.code)
        } catch (uncertain: Uncertain) {
            return SonyPtpInitializationResult.Uncertain(uncertain.stage)
        } catch (failed: Failure) {
            return SonyPtpInitializationResult.Failed(failed.stage)
        } catch (_: IllegalArgumentException) {
            return SonyPtpInitializationResult.Failed("protocol")
        } finally {
            if (!ready) closeRemoteSession()
        }
    }

    suspend fun closeRemoteSession() {
        if (!sessionOpen) return
        sessionOpen = false
        withContext(NonCancellable) {
            withTimeoutOrNull(3.seconds) {
                commands.executeNoData(SonyPtpOperation.CLOSE_SESSION)
            }
        }
    }
}
