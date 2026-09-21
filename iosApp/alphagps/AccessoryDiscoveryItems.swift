import AccessorySetupKit
import Foundation

/// Discovery happens before authorization, so a Bluetooth UUID is not required.
/// Keep unnamed/UUID-less discoveries distinct by object identity, never by the
/// advertised model name (two nearby cameras can advertise the same name).
///
/// Concrete rather than generic on purpose. As a generic class with an
/// `AnyObject`-constrained parameter, swift-frontend 6.3.3 crashed in the
/// EarlyPerfInliner pass on this type's synthesized deinit — release builds
/// only, since that pass does not run at -Onone. There is exactly one
/// instantiation, so the generics bought nothing but the compiler bug.
@available(iOS 26.1, *)
final class AccessoryDiscoveryItems {
    private struct Entry {
        var accessory: ASDiscoveredAccessory
        var bluetoothIdentifier: UUID?
        var item: ASDiscoveredDisplayItem
    }

    private var entries: [Entry] = []

    var items: [ASDiscoveredDisplayItem] {
        entries.map(\.item)
    }

    func upsert(
        accessory: ASDiscoveredAccessory,
        bluetoothIdentifier: UUID?,
        item: ASDiscoveredDisplayItem
    ) {
        let index = entries.firstIndex { entry in
            entry.accessory === accessory ||
                (bluetoothIdentifier != nil && entry.bluetoothIdentifier == bluetoothIdentifier)
        }
        if let index {
            entries[index] = Entry(
                accessory: accessory,
                bluetoothIdentifier: bluetoothIdentifier ?? entries[index].bluetoothIdentifier,
                item: item
            )
        } else {
            entries.append(Entry(accessory: accessory, bluetoothIdentifier: bluetoothIdentifier, item: item))
        }
    }

    func removeAll() { entries.removeAll() }
}
