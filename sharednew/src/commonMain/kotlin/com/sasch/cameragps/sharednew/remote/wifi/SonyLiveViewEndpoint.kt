package com.sasch.cameragps.sharednew.remote.wifi

/** Only the literal IPv4 peer of the PTP connection may serve the advertised stream. */
internal class SonyLiveViewEndpoint private constructor(val url: String) {
    override fun toString() = "SonyLiveViewEndpoint(redacted)"

    companion object {
        fun fromUrl(url: String, cameraHost: String): SonyLiveViewEndpoint {
            val address = cameraHost.split('.')
            require(address.size == 4 && address.all {
                it.toIntOrNull()?.let { value -> value in 0..255 && value.toString() == it } == true
            }) { "Camera peer must be a literal IPv4 address" }
            require(url.length <= 2048 && url.all { it.code in 0x21..0x7e } && '#' !in url && '\\' !in url) {
                "Invalid live-view URL"
            }
            require(url.startsWith("http://")) { "Unsupported live-view scheme" }
            val authority = url.removePrefix("http://").takeWhile { it != '/' && it != '?' }
            val parts = authority.split(':')
            require(parts.size in 1..2 && parts[0] == cameraHost) { "Live view must target the camera peer" }
            if (parts.size == 2) require(parts[1].toIntOrNull() in 1..65535) { "Invalid live-view port" }
            return SonyLiveViewEndpoint(url)
        }

        fun fromDeviceDescription(dd: ByteArray, cameraHost: String): SonyLiveViewEndpoint {
            require(dd.size <= 64 * 1024) { "DD XML is too large" }
            val xml = dd.decodeToString(throwOnInvalidSequence = true)
                .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            require(!xml.contains("<!")) { "Unsupported DD XML declaration" }
            val tag = Regex("""<((?:[A-Za-z_][\w.-]*:)?X_ScalarWebAPI_LiveView_URL)(?:\s+[^<>]*)?>([^<]*)</\1\s*>""")
            val matches = tag.findAll(xml).toList()
            require(matches.size == 1) { "No unambiguous live-view URL in DD" }
            val text = matches.single().groupValues[2].trim()
            require(!Regex("&(?!amp;|lt;|gt;|quot;|apos;)").containsMatchIn(text)) { "Unsupported URL entity" }
            val url = text.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            return fromUrl(url, cameraHost)
        }
    }
}
