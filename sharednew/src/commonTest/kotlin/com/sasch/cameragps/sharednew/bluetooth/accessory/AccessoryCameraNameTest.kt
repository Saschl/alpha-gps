package com.sasch.cameragps.sharednew.bluetooth.accessory

import kotlin.test.Test
import kotlin.test.assertEquals

class AccessoryCameraNameTest {
    @Test
    fun genericPickerNameFallsBackToHardwareName() {
        assertEquals("ILCE-6700", AccessoryCameraName.resolve("Camera", "ILCE-6700", "Camera"))
    }

    @Test
    fun legacyAndBlankPlaceholdersDoNotHideHardwareNames() {
        for (placeholder in listOf(null, "", "  ", " Camera ", "Sony camera", "N/A", "Unknown device")) {
            assertEquals("ILCE-6700", AccessoryCameraName.resolve(placeholder, " ILCE-6700 ", placeholder))
        }
    }

    @Test
    fun explicitAccessoryRenameWinsOverHardwareAndPreviouslySavedName() {
        assertEquals("Travel camera", AccessoryCameraName.resolve("Travel camera", "ILCE-6700", "ILCE-6700"))
    }

    @Test
    fun savedCustomNameSurvivesMissingOrGenericSystemMetadata() {
        assertEquals("My camera", AccessoryCameraName.resolve(null, "ILCE-6700", "My camera"))
        assertEquals("My camera", AccessoryCameraName.resolve("Camera", "ILCE-6700", "My camera"))
    }

    @Test
    fun savedHardwareNameSurvivesDisconnectionAndRestart() {
        assertEquals("ILCE-6700", AccessoryCameraName.resolve("Camera", null, "ILCE-6700"))
    }

    @Test
    fun missingHardwareNameKeepsGenericFallback() {
        assertEquals("Camera", AccessoryCameraName.resolve("Camera", null, "N/A"))
    }
}
