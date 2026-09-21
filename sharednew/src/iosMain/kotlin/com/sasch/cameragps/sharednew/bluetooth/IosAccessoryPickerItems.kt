package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryCameraName
import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AccessorySetupKit.ASAccessorySupportBluetoothPairingLE
import platform.AccessorySetupKit.ASDiscoveryDescriptor
import platform.AccessorySetupKit.ASMigrationDisplayItem
import platform.AccessorySetupKit.ASPickerDisplayItem
import platform.Foundation.NSUUID
import platform.UIKit.UIImage

@OptIn(ExperimentalForeignApi::class)
internal object IosAccessoryPickerItems {

    fun discovery(): ASPickerDisplayItem = ASPickerDisplayItem(
        name = AccessoryCameraName.FALLBACK,
        productImage = productImage(),
        descriptor = sonyDescriptor(),
        // Temporarily use the standard setup flow without a rename step while
        // testing the extra system scan confirmation. Existing accessories can
        // still be renamed separately.
    )//.apply {
        // On iOS 26.1+ Swift replaces the initial label with the advertised name.
        // Older systems still offer the native rename step with this fallback.
    // setSetupOptions(ASPickerDisplayItemSetupRename)
    // }

    fun migration(candidates: List<PendingMigration>): List<ASMigrationDisplayItem> {
        val image = productImage()
        return candidates.map { candidate ->
            ASMigrationDisplayItem(
                name = candidate.displayName,
                productImage = image,
                descriptor = sonyDescriptor(),
            ).apply {
                setPeripheralIdentifier(NSUUID(uUIDString = candidate.identifier))
            }
        }
    }

    private fun productImage(): UIImage = IosAccessoryArtwork.image

    private fun sonyDescriptor(): ASDiscoveryDescriptor = ASDiscoveryDescriptor().apply {
        // Sony does not advertise the location service UUID. Keep the existing
        // company-ID matcher, declared in NSAccessorySetupBluetoothCompanyIdentifiers.
        setBluetoothCompanyIdentifier(0x012Du)
        setSupportedOptions(ASAccessorySupportBluetoothPairingLE)
    }
}
