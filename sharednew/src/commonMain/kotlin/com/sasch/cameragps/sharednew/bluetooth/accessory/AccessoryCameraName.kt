package com.sasch.cameragps.sharednew.bluetooth.accessory

/** The system display name and Bluetooth hardware name are separate values. */
object AccessoryCameraName {
    const val FALLBACK = "Camera"

    private fun meaningful(name: String?): String? = name?.trim()?.takeUnless {
        it.isEmpty() || it.lowercase() in setOf("camera", "sony camera", "n/a", "unknown device")
    }

    /** Keep user names; upgrade generic defaults as soon as a hardware name is available. */
    fun resolve(accessoryName: String?, bluetoothName: String?, savedName: String?): String =
        meaningful(accessoryName)
            ?: meaningful(savedName)
            ?: meaningful(bluetoothName)
            ?: FALLBACK
}
