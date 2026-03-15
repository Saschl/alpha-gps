# iosApp

This folder contains the iOS host application that embeds the shared Kotlin Multiplatform framework.

## Prerequisites

- Xcode 16+
- Kotlin Multiplatform Mobile plugin for Android Studio (optional, for editing Kotlin code)
- The `:shared` Gradle module must be built first to produce the framework

---

## Setup steps

### 1. Build the shared framework

From the repo root:

```bash
./gradlew :shared:assembleReleaseXCFramework
# or for simulator/debug builds:
./gradlew :shared:assembleDebugXCFramework
```

This produces:
```
shared/build/XCFrameworks/debug/CameraGpsShared.xcframework
shared/build/XCFrameworks/release/CameraGpsShared.xcframework
```

### 2. Create the Xcode project

1. Open Xcode → File → New → Project → App (iOS)
2. Product name: **iosApp**, bundle ID: `com.saschl.cameragps.ios`
3. Language: **Swift**, Interface: **SwiftUI**
4. Save the project inside this `iosApp/` folder

### 3. Link the shared XCFramework

1. In Xcode, drag `CameraGpsShared.xcframework` into the project navigator
2. In **Build Phases → Link Binary With Libraries** ensure it appears
3. Under **Frameworks, Libraries, and Embedded Content** set it to **Embed & Sign**

### 4. Set the entry point

Replace the default `ContentView.swift` with:

```swift
import SwiftUI
import CameraGpsShared

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct iosApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea()
        }
    }
}
```

### 5. Add Bluetooth permissions

In your `Info.plist` add:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>CameraGPS needs Bluetooth to connect to your Sony camera.</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>CameraGPS needs Bluetooth to connect to your Sony camera.</string>
```

### 6. Build and run

Select an iPhone simulator or physical device and press ⌘R.

---

## Architecture notes

| Layer | Location | Description |
|---|---|---|
| UI screens | `shared/src/commonMain/…/shared/ui/` | Compose Multiplatform composables used on both Android & iOS |
| Bluetooth | `shared/src/iosMain/…/bluetooth/IosBluetoothController.kt` | CoreBluetooth implementation |
| iOS entry | `shared/src/iosMain/…/MainViewController.kt` | `ComposeUIViewController` host exported to Swift |
| Android entry | `app/src/main/java/…/MainActivity.kt` | Standard Android Activity |
| Database | `shared/src/commonMain/…/database/` | Room KMP entities & DAOs |

---

## CI

To build the XCFramework in CI, add a step:

```yaml
- name: Build XCFramework
  run: ./gradlew :shared:assembleReleaseXCFramework
```

