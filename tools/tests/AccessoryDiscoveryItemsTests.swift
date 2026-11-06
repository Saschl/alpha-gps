import Foundation

final class AccessoryDiscoveryItemsTests {
    private final class Accessory {}

    func testDiscoveryWithoutBluetoothUUIDIsIncluded() {
        let discoveries = AccessoryDiscoveryItems<Accessory, String>()
        discoveries.upsert(accessory: Accessory(), bluetoothIdentifier: nil, item: "ILCE-6700")
        precondition(discoveries.items == ["ILCE-6700"], "UUID-less discoveries must be included")
    }

    func testIdenticalModelNamesDoNotMergeUUIDLessCameras() {
        let discoveries = AccessoryDiscoveryItems<Accessory, String>()
        discoveries.upsert(accessory: Accessory(), bluetoothIdentifier: nil, item: "ILCE-6700")
        discoveries.upsert(accessory: Accessory(), bluetoothIdentifier: nil, item: "ILCE-6700")
        precondition(discoveries.items == ["ILCE-6700", "ILCE-6700"], "Identical names must not merge cameras")
    }

    func testRepeatedDiscoveryUpdatesInPlaceAndCanAcquireUUID() {
        let discoveries = AccessoryDiscoveryItems<Accessory, String>()
        let accessory = Accessory()
        let identifier = UUID()
        discoveries.upsert(accessory: accessory, bluetoothIdentifier: nil, item: "Camera")
        discoveries.upsert(accessory: accessory, bluetoothIdentifier: identifier, item: "ILCE-6700")
        // A later event can contain a different native object for the same UUID.
        discoveries.upsert(accessory: Accessory(), bluetoothIdentifier: identifier, item: "Updated camera")
        precondition(discoveries.items == ["Updated camera"], "Repeat discoveries must update the existing entry")
    }

    func testUpdatesPreserveOtherCamerasAndCleanupRemovesAll() {
        let discoveries = AccessoryDiscoveryItems<Accessory, String>()
        let first = UUID()
        discoveries.upsert(accessory: Accessory(), bluetoothIdentifier: first, item: "First")
        discoveries.upsert(accessory: Accessory(), bluetoothIdentifier: UUID(), item: "Second")
        discoveries.upsert(accessory: Accessory(), bluetoothIdentifier: first, item: "First updated")
        precondition(discoveries.items == ["First updated", "Second"], "Updates must preserve other cameras")
        discoveries.removeAll()
        precondition(discoveries.items.isEmpty, "Cleanup must remove discoveries from the previous picker")
    }
}

@main
enum AccessoryDiscoveryItemsTestRunner {
    static func main() {
        let tests = AccessoryDiscoveryItemsTests()
        tests.testDiscoveryWithoutBluetoothUUIDIsIncluded()
        tests.testIdenticalModelNamesDoNotMergeUUIDLessCameras()
        tests.testRepeatedDiscoveryUpdatesInPlaceAndCanAcquireUUID()
        tests.testUpdatesPreserveOtherCamerasAndCleanupRemovesAll()
        print("Passed 4 accessory discovery regression tests")
    }
}
