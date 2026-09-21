package com.sasch.cameragps.sharednew.bluetooth.accessory

/** A camera name together with whether a person chose it. */
data class ResolvedCameraName(val name: String, val isCustom: Boolean)

/**
 * Resolves the one name the app displays for a camera.
 *
 * The system display name and the Bluetooth hardware name are separate values,
 * and neither is authoritative on its own. Rather than guessing whether a string
 * looks like a placeholder — which silently overwrote anyone who deliberately
 * named a camera "Camera" — this trusts two facts:
 *
 * - The app sets the AccessorySetupKit display item name to [FALLBACK] itself,
 *   so an accessory name that differs from it can only have been typed by a
 *   person in the system rename sheet.
 * - A rename made in the app is stored with `deviceNameIsCustom`.
 *
 * A custom name is therefore never replaced, while a derived one is upgraded as
 * soon as a real hardware name becomes available (which, for AccessorySetupKit
 * cameras, is only after authorization).
 */
object AccessoryCameraName {
    const val FALLBACK = "Camera"

    private fun String?.clean(): String? = this?.trim()?.takeUnless { it.isEmpty() }

    fun resolve(
        accessoryName: String?,
        bluetoothName: String?,
        savedName: String?,
        savedNameIsCustom: Boolean = false,
    ): ResolvedCameraName {
        // A system rename is the newest expression of intent, and on iOS it is
        // the only way an accessory name can differ from the default we set.
        val renamedInSystem = accessoryName.clean()?.takeUnless { it == FALLBACK }
        if (renamedInSystem != null) return ResolvedCameraName(renamedInSystem, isCustom = true)

        val saved = savedName.clean()
        if (savedNameIsCustom && saved != null) return ResolvedCameraName(saved, isCustom = true)

        // Prefer the live hardware name so a camera saved before its name was
        // readable heals itself on the next connection.
        val hardware = bluetoothName.clean()
        if (hardware != null) return ResolvedCameraName(hardware, isCustom = false)

        // Nothing live: keep whatever hardware name was stored last.
        return ResolvedCameraName(saved ?: FALLBACK, isCustom = false)
    }

    /** Convenience for call sites that only render the name. */
    fun resolveName(
        accessoryName: String?,
        bluetoothName: String?,
        savedName: String?,
        savedNameIsCustom: Boolean = false,
    ): String = resolve(accessoryName, bluetoothName, savedName, savedNameIsCustom).name
}
