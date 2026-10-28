package com.sasch.cameragps.sharednew.diagnostics

import com.sasch.cameragps.sharednew.database.devices.CameraDevice

/** Export-only redaction; local logs remain useful for on-device inspection. */
internal class ReportRedactor(labels: Map<String, String>, devices: List<CameraDevice>) {
    private val identifiers = labels.toMutableMap()
    private val names = devices.filter { it.deviceName.isNotBlank() && it.deviceName != "N/A" }
        .sortedByDescending { it.deviceName.length }
        .map {
            Regex(
                "(?<![\\p{L}\\p{N}_])${Regex.escape(it.deviceName)}(?![\\p{L}\\p{N}_])",
                RegexOption.IGNORE_CASE
            ) to
                    labels.getValue(it.mac.uppercase())
        }

    fun redact(text: String): String {
        // Omit the whole message, including multiline payloads, rather than trying to parse coordinates/secrets.
        if (sensitive.containsMatchIn(text)) return "[Sensitive location or credential details omitted]"
        var result = text
        names.forEach { (name, label) -> result = name.replace(result) { label } }
        result = identifier.replace(result) {
            val id = it.value.uppercase().replace(':', '-')
            // Bluetooth service/characteristic UUIDs describe the protocol, not an individual camera.
            if (id.endsWith("-0000-1000-8000-00805F9B34FB")) it.value
            else identifiers.entries.firstOrNull { (key, _) -> key.replace(':', '-') == id }?.value
                ?: identifiers.getOrPut(id) { "Device ${identifiers.size + 1}" }
        }
        return result
    }

    private companion object {
        val identifier =
            Regex("(?i)(?:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})")
        val sensitive = Regex(
            "(?i)(\\b(latitude|longitude|lat|lon|lng|altitude|coordinates?)\\s*[=:]|" +
                    "\\b(?:GeoLocation|CLLocation|Location)\\s*[\\[(]|" +
                    "\\b(?:location|gps)\\s+(?:packet|payload|data)\\b|" +
                    "\\b(?:password|passphrase|credential|credentials|ssid|bssid|psk)\\b|" +
                    "[-+]?\\d{1,3}\\.\\d+\\s*[,/]\\s*[-+]?\\d{1,3}\\.\\d+)",
        )
    }
}
