# AccessorySetupKit camera names

## Before discovery

On iOS, Add Camera opens `SharedPairingPreparationScreen` before the system
picker. Its English/German instructions explain how to enable Bluetooth, start
pairing on the camera, and keep that screen open. Only **Search for camera**
opens the picker; Back and Need help do not start discovery. The existing
troubleshooting guide returns to the preparation screen when opened from here.

`PairingPreparationState` disables repeat searches while a picker is pending and
re-enables the controls after it returns. An unsuccessful picker result leaves
the instructions open for retry; a successful result returns to Devices. The
screen and state live in commonMain for reuse by Android, but Android's current
setup flow is unchanged. Run the `*PairingPreparation*` tests for these guards.

## Picker names

The picker artwork is the original, unbranded SVG in
`iosApp/alphagps/Assets.xcassets/AccessoryCamera.imageset/camera.svg`. It has a
transparent 540 × 360 canvas (Apple's 180 × 120 pt image frame at 3×), retains
vector data, and uses original colors that remain visible on light and dark
backgrounds. Replace that SVG to change the artwork; the app icon is separate.
`IosAccessoryArtwork.image` loads it once for Kotlin discovery/migration items
and Swift's dynamic discoveries. Its oversized SF Symbol fallback also avoids
enlarging a tiny default symbol when the asset catalog is absent in tests.

New camera setup offers Apple's native rename step on iOS 18 and later.
On iOS 26.1 and later, the discovery picker prefers the accessory's advertised
Bluetooth local name (for example, `ILCE-6700`) as its initial name, then the
accessory's system display name, with `Camera` as the last fallback. In practice
Sony cameras reach the picker as `Camera`, because neither of the first two
values arrives — see the next paragraph. Names are not limited to a model list.
On iOS 18–26.0, the initial picker label is `Camera` too; those systems do not
expose the pre-authorization discovery customization API at all.

**The advertised name is not reachable before authorization.** Maintainer testing
on hardware confirmed the camera's local name is absent from
`ASDiscoveredAccessory.bluetoothAdvertisementData`, even while the picker shows
`ILCE-6700` as its own subtitle. A resolver that tried the CoreBluetooth key,
alternative key spellings, the Bluetooth assigned numbers `0x08`/`0x09`, and a
length/type/value walk of the raw payload was written, tested and **reverted** —
it found nothing to read. Do not retry this: the dictionary is the only
pre-authorization channel in the framework (`ASAccessoryEvent` carries only
`eventType`/`accessory`/`error`, and `ASDiscoveredAccessory` adds only that
dictionary and `bluetoothRSSI`), and the name appears to be withheld by design.
The system clearly holds it — `ASDiscoveryDescriptor.bluetoothNameSubstring`
matches on the over-the-air name — but only ever offers
`bluetoothManufacturerDataBlob`/`Mask` for identifying a product. Deriving a
model name from Sony's manufacturer payload would need a model-code table, which
this app deliberately avoids.

`AccessoryDiscoveryNaming.swift` handles the newer discovery APIs behind Swift's
availability checks. **Do not move references to ASDiscoveredAccessory,
ASDiscoveredDisplayItem, or ASPickerDisplaySettings into Kotlin.** Kotlin/Native
strongly links class references even if runtime version checks surround them,
which can prevent the app from launching on an older OS. The shared module's
`IosAccessoryDiscoverySupport` bridge only mentions iOS 18 types.

Registration in AppDelegate is inert; the accessory shell creates the adapter
only for a user-initiated discovery picker. The adapter retains all discovered
cameras, serializes picker updates, stops updating names once setup begins, and
clears its settings at completion. Migration still uses only migration items,
with their existing names and no new rename/setup steps.

For discovery, a successful `showPicker` callback does **not** release the
picker wait or stop the adapter. The visible picker's `pickerDidDismiss` event
owns cleanup. Otherwise an early callback can disable the handler while the
system is still scanning, leaving app-filtered results empty. Errors still
release the wait immediately. Migration keeps its existing callback and
`migrationComplete` terminal paths because it may have no visible picker.
Logs distinguish the show-picker callback, presentation, dismissal, discovery
updates, and adapter shutdown; use their order when debugging on-device.

Discovered accessories must not be filtered on `bluetoothIdentifier`: this is
optional before authorization. `AccessoryDiscoveryItems` retains entries using
object identity when the UUID is absent, and uses UUIDs when available to merge
repeat events. Never deduplicate by the model name, since multiple nearby cameras
may advertise the same name.

At dismissal the shell reads the latest authorized accessory snapshot so the
selected name, including a user rename, reaches the device record.

The picker's hardware-name subtitle is separate from its app-provided display
name. If discovery does not expose a Bluetooth local name, the system may still
show `Camera` with `ILCE-6700` underneath. After authorization, the controller
uses the retrieved `CBPeripheral.name` as a fallback. `AccessoryCameraName`
chooses a non-placeholder AccessorySetupKit name first, then a saved meaningful
name, then the Bluetooth name, and finally `Camera`. The reserved placeholders
are `Camera`, the old `Sony camera`, `N/A`, `Unknown device`, and blank values.
This keeps custom names while upgrading default names, including existing
saved cameras when their hardware name becomes available on reconnect.

The resolved name is persisted with a name-only SQL update; insert-if-absent
alone would leave an existing `Camera` row unchanged. Device enabled state,
Always On, remote control, and handshake delay remain untouched. This does not
rename the system's accessory record or change the native picker's subtitle.

Run `./gradlew :sharednew:iosSimulatorArm64Test --tests '*Accessory*'` for the
picker configuration and migration regressions. A real camera is required to
verify the Bluetooth discovery and native rename UI: test accepting the default
name, entering a custom name, reopening the app, and two nearby cameras.

The Swift discovery regression checks run on macOS without camera hardware:

```sh
test_dir=$(mktemp -d /private/tmp/alpha-gps-discovery-tests.XXXXXX)
xcrun swiftc iosApp/alphagps/AccessoryDiscoveryItems.swift \
  tools/tests/AccessoryDiscoveryItemsTests.swift -o "$test_dir/discovery-tests"
"$test_dir/discovery-tests"
```

References: [Apple's discovery customization](https://developer.apple.com/documentation/accessorysetupkit/discovering-and-configuring-accessories)
and [Kotlin/Native class availability](https://kotlinlang.org/docs/native-lib-import-stability.html#using-new-objective-c-classes-from-platform-libraries).
