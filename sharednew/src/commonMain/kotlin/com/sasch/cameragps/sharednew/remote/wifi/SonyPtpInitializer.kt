package com.sasch.cameragps.sharednew.remote.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

internal sealed interface SonyPtpInitializationResult {
    data class Ready(
        val serverVersion: String?,
        val vendorCodeVersion: Long?,
        val extendedInfo: SonyExtendedDeviceInfo,
        val deviceInfo: PtpDeviceInfo,
        /** Held in memory only for later live-view endpoint discovery. */
        val deviceDescription: ByteArray?,
    ) : SonyPtpInitializationResult {
        override fun toString() = "SonyPtpReady(serverVersion=$serverVersion, vendorCodeVersion=$vendorCodeVersion)"
    }

    data class Rejected(val stage: String, val responseCode: Int) : SonyPtpInitializationResult
    data class Uncertain(val stage: String) : SonyPtpInitializationResult
    data class Failed(val stage: String) : SonyPtpInitializationResult
}

/** Sony's observed DD/DID → session → three-connect handshake on the a6700. */
internal class SonyPtpInitializer(private val commands: PtpIpCommandQueue) {
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
        val result = if (dataIn) commands.executeDataIn(code, parameters)
        else commands.executeNoData(code, parameters)
        return when (result) {
            is PtpIpTransactionResult.Response -> {
                if (result.response.code != 0x2001) throw Rejection(stage, result.response.code)
                result
            }
            PtpIpTransactionResult.Uncertain -> throw Uncertain(stage)
            is PtpIpTransactionResult.Failure, PtpIpTransactionResult.Closed -> throw Failure(stage)
        }
    }

    suspend fun initialize(): SonyPtpInitializationResult {
        if (initializationStarted) return SonyPtpInitializationResult.Failed("already_initialized")
        initializationStarted = true
        var ready = false
        try {
            val deviceInfo = when (val result = PtpIpCapabilityReader(commands).read()) {
                is PtpIpCapabilityResult.Available -> result.deviceInfo
                is PtpIpCapabilityResult.Rejected -> throw Rejection("get_device_info", result.responseCode)
                PtpIpCapabilityResult.Uncertain -> throw Uncertain("get_device_info")
                PtpIpCapabilityResult.Failed -> throw Failure("get_device_info")
            }
            if (!deviceInfo.supports(SonyPtpOperation.SDIO_CONNECT) ||
                !deviceInfo.supports(SonyPtpOperation.SDIO_GET_EXT_DEVICE_INFO)) {
                throw Failure("unsupported_camera")
            }

            var description: ByteArray? = null
            var serverVersion: String? = null
            if (deviceInfo.supports(SonyPtpOperation.SDIO_GET_DEVICE_DESCRIPTION_FILE)) {
                description = operation("get_dd_xml", SonyPtpOperation.SDIO_GET_DEVICE_DESCRIPTION_FILE,
                    listOf(1), dataIn = true).data ?: throw Failure("get_dd_xml")
                require(description.size <= 64 * 1024) { "DD XML is too large" }
                val did = operation("get_did_xml", SonyPtpOperation.SDIO_GET_DEVICE_DESCRIPTION_FILE,
                    listOf(2), dataIn = true).data ?: throw Failure("get_did_xml")
                serverVersion = try { SonyDidVersion.parse(did) } catch (_: IllegalArgumentException) {
                    throw Failure("get_did_xml")
                }
            }
            val remoteWithTransfer = serverVersion?.substringBefore('.')?.toIntOrNull()?.let { it >= 4 } ?: false
            if (remoteWithTransfer) {
                if (!deviceInfo.supports(SonyPtpOperation.SDIO_OPEN_SESSION)) throw Failure("unsupported_session")
                operation("open_session", SonyPtpOperation.SDIO_OPEN_SESSION, listOf(1, 2))
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
            ready = true
            return SonyPtpInitializationResult.Ready(
                serverVersion, vendorVersion, extendedInfo, deviceInfo, description)
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
            withTimeoutOrNull(3_000) {
                commands.executeNoData(SonyPtpOperation.CLOSE_SESSION)
            }
        }
    }
}
