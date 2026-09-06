import Foundation

/// Discovery happens before authorization, so a Bluetooth UUID is not required.
/// Keep unnamed/UUID-less discoveries distinct by object identity, never by the
/// advertised model name (two nearby cameras can advertise the same name).
final class AccessoryDiscoveryItems<Accessory: AnyObject, Item> {
    private struct Entry {
        var accessory: Accessory
        var bluetoothIdentifier: UUID?
        var item: Item
    }

    private var entries: [Entry] = []

    var items: [Item] { entries.map(\.item) }

    func upsert(accessory: Accessory, bluetoothIdentifier: UUID?, item: Item) {
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
