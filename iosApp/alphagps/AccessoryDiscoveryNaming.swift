import AccessorySetupKit
import CoreBluetooth
import UIKit
import sharedKit

enum AccessoryDiscoveryNaming {
    static func install() {
        IosAccessoryDiscoverySupport.shared.install { session in
            if #available(iOS 26.1, *) {
                return DiscoveryPickerNames(session: session)
            }
            return nil
        }
    }
}

/// AccessorySetupKit mechanics only; callbacks arrive on the session's main queue.
/// Availability must live in Swift so new system classes are weakly linked.
@available(iOS 26.1, *)
private final class DiscoveryPickerNames: NSObject, IosAccessoryDiscoveryCustomizer {
    private let session: ASAccessorySession
    private let discoveries = AccessoryDiscoveryItems()
    private var active = false
    private var acceptingDiscoveries = true
    private var updating = false
    private var needsUpdate = false
    private var initialBatchReady = false
    private var initialBatchWorkItem: DispatchWorkItem?
    private var discoveryEventCount = 0

    init(session: ASAccessorySession) {
        self.session = session
        super.init()
    }

    func start() {
        active = true
        let settings = ASPickerDisplaySettings.default
        settings.options.insert(.filterDiscoveryResults)
        // Retain the system's bounded discovery timeout.
        session.pickerDisplaySettings = settings
        NSLog("Accessory picker naming started")
    }

    func onEvent(event: ASAccessoryEvent) {
        guard active else { return }
        switch event.eventType {
        case .accessoryDiscovered:
            guard acceptingDiscoveries else {
                NSLog("Accessory picker ignoring discovery after setup started")
                return
            }
            guard let accessory = event.accessory as? ASDiscoveredAccessory else {
                NSLog("Accessory picker discovery event did not contain ASDiscoveredAccessory")
                return
            }
            let advertisedName = (accessory.bluetoothAdvertisementData?[CBAdvertisementDataLocalNameKey] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let fallback = accessory.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = advertisedName.flatMap { $0.isEmpty ? nil : $0 }
                ?? (fallback.isEmpty ? "Camera" : fallback)
            let item = ASDiscoveredDisplayItem(
                name: name,
                productImage: IosAccessoryArtwork.shared.image,
                accessory: accessory
            )
            item.setupOptions.insert(.rename)
            discoveries.upsert(accessory: accessory, bluetoothIdentifier: accessory.bluetoothIdentifier, item: item)
            discoveryEventCount += 1
            NSLog("Accessory picker received discovery #%ld (%ld distinct item(s), initial collection: %@, Bluetooth identifier available: %@)",
                  discoveryEventCount, discoveries.items.count, initialBatchReady ? "no" : "yes",
                  accessory.bluetoothIdentifier == nil ? "no" : "yes")
            needsUpdate = true
            updatePicker()
        case .accessoryAdded, .pickerSetupPairing, .pickerSetupRename:
            // Once setup starts, new advertisements must not replace the name
            // while the user is editing it in the system rename step.
            acceptingDiscoveries = false
            needsUpdate = false
            cancelInitialBatch()
            NSLog("Accessory picker setup started; discovery updates stopped")
        case .pickerDidDismiss, .invalidated:
            stop()
        default:
            break
        }
    }

    /// Serialize updates and retain all discoveries so multiple nearby cameras
    /// remain selectable even if a new advertisement arrives during an update.
    private func updatePicker() {
        guard active, acceptingDiscoveries, needsUpdate, !updating else { return }
        // Experiment: collect for a fixed window after the first discovery before
        // presenting any results. Later advertisements must not restart the timer.
        guard initialBatchReady else {
            if initialBatchWorkItem == nil {
                NSLog("Accessory picker collecting discoveries for 3 seconds before first update")
                let workItem = DispatchWorkItem { [weak self] in
                    guard let self, self.active, self.acceptingDiscoveries else {
                        return
                    }
                    self.initialBatchWorkItem = nil
                    self.initialBatchReady = true
                    NSLog("Accessory picker initial collection finished: %ld discovery event(s), %ld distinct item(s)",
                          self.discoveryEventCount, self.discoveries.items.count)
                    self.updatePicker()
                }
                initialBatchWorkItem = workItem
                DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: workItem)
            }
            return
        }
        needsUpdate = false
        updating = true
        NSLog("Accessory picker submitting %ld discovered item(s)", discoveries.items.count)
        session.updatePicker(showing: discoveries.items) { [weak self] error in
            guard let self, self.active else { return }
            self.updating = false
            if let error {
                NSLog("Accessory picker name update failed: %@", error.localizedDescription)
            } else {
                NSLog("Accessory picker name update succeeded")
            }
            self.updatePicker()
        }
    }

    func stop() {
        guard active else { return }
        active = false
        needsUpdate = false
        cancelInitialBatch()
        NSLog("Accessory picker naming stopped after %ld discovered item(s)", discoveries.items.count)
        discoveries.removeAll()
        // Migration must keep its original, migration-only picker behavior.
        session.pickerDisplaySettings = nil
    }

    private func cancelInitialBatch() {
        initialBatchWorkItem?.cancel()
        initialBatchWorkItem = nil
    }
}
