package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessoryCameraNameTest {
    @Test
    fun genericPickerNameFallsBackToHardwareName() {
        val resolved = AccessoryCameraName.resolve("Camera", "ILCE-6700", "Camera")
        assertEquals("ILCE-6700", resolved.name)
        assertFalse(resolved.isCustom, "A hardware name is derived, not chosen")
    }

    @Test
    fun blankMetadataDoesNotHideHardwareNames() {
        for (blank in listOf(null, "", "  ", " Camera ")) {
            assertEquals("ILCE-6700", AccessoryCameraName.resolveName(blank, " ILCE-6700 ", blank))
        }
    }

    @Test
    fun systemRenameWinsOverHardwareAndPreviouslySavedName() {
        val resolved = AccessoryCameraName.resolve("Travel camera", "ILCE-6700", "ILCE-6700")
        assertEquals("Travel camera", resolved.name)
        assertTrue(resolved.isCustom, "A name typed in the system sheet is a chosen name")
    }

    @Test
    fun customSavedNameSurvivesMissingOrDefaultSystemMetadata() {
        for (accessoryName in listOf(null, "Camera")) {
            val resolved =
                AccessoryCameraName.resolve(accessoryName, "ILCE-6700", "My camera", savedNameIsCustom = true)
            assertEquals("My camera", resolved.name)
            assertTrue(resolved.isCustom)
        }
    }

    /**
     * The old placeholder list treated these strings as "not a real name" and
     * overwrote them with the hardware name. A stored flag cannot misread intent.
     */
    @Test
    fun deliberateNamesThatLookLikePlaceholdersAreKept() {
        for (name in listOf("Camera", "Sony camera", "N/A", "Unknown device")) {
            assertEquals(
                name,
                AccessoryCameraName.resolveName(null, "ILCE-6700", name, savedNameIsCustom = true),
                "A chosen name must never be replaced by the hardware name",
            )
        }
    }

    @Test
    fun derivedNamesAreUpgradedWhenABetterHardwareNameAppears() {
        assertEquals(
            "ILCE-6700",
            AccessoryCameraName.resolveName(null, "ILCE-6700", "N/A", savedNameIsCustom = false),
            "A name nobody chose should heal once the hardware name is readable",
        )
    }

    @Test
    fun savedNameSurvivesDisconnectionAndRestart() {
        assertEquals("ILCE-6700", AccessoryCameraName.resolveName("Camera", null, "ILCE-6700"))
    }

    @Test
    fun missingEverythingKeepsTheGenericFallback() {
        val resolved = AccessoryCameraName.resolve(null, null, null)
        assertEquals(AccessoryCameraName.FALLBACK, resolved.name)
        assertFalse(resolved.isCustom)
    }
}
