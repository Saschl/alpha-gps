package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AccessorySetupKit.ASAccessorySupportBluetoothPairingLE
import platform.UIKit.UIImageRenderingMode.UIImageRenderingModeAlwaysOriginal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosAccessoryPickerItemsTest {
    @Test
    fun discoveryAndMigrationUseTheSameHighResolutionOriginalArtwork() {
        val image = IosAccessoryArtwork.image
        // Kotlin/Native can wrap the same native UIImage with different wrappers.
        assertEquals(image, IosAccessoryPickerItems.discovery().productImage)
        val migration = IosAccessoryPickerItems.migration(listOf(
            PendingMigration("00000000-0000-0000-0000-000000000001", "ILCE-6700"),
        ))
        assertEquals(image, migration.single().productImage)
        assertEquals(UIImageRenderingModeAlwaysOriginal, image.renderingMode)
        image.size.useContents {
            assertTrue(width * image.scale >= 540, "Artwork must cover a 180 pt frame at 3×")
            assertTrue(height * image.scale >= 360, "Artwork must cover a 120 pt frame at 3×")
        }
    }

    @Test
    fun discoverySkipsExtraSetupStepsWithoutChangingTheBluetoothMatcherOrPairing() {
        val item = IosAccessoryPickerItems.discovery()
        assertEquals(0uL, item.setupOptions)
        assertEquals(0x012Du.toUShort(), item.descriptor.bluetoothCompanyIdentifier)
        assertEquals(ASAccessorySupportBluetoothPairingLE, item.descriptor.supportedOptions)
    }

    @Test
    fun migrationKeepsSavedNamesAndIdentifiersWithoutAddingSetupSteps() {
        val cameras = listOf(
            PendingMigration("00000000-0000-0000-0000-000000000001", "ILCE-6700"),
            PendingMigration("00000000-0000-0000-0000-000000000002", "My travel camera"),
        )
        val items = IosAccessoryPickerItems.migration(cameras)
        assertEquals(cameras.map { it.displayName }, items.map { it.name })
        assertEquals(cameras.map { it.identifier }, items.map { it.peripheralIdentifier?.UUIDString })
        // A rename step must not turn the migration-only operation into visible
        // discovery/setup. The return type also excludes regular display items.
        assertTrue(items.all { it.setupOptions == 0uL })
        assertTrue(items.all { it.descriptor.bluetoothCompanyIdentifier == 0x012Du.toUShort() })
    }
}
