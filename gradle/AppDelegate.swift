import UIKit

/// AppDelegate adapter that ensures the CoreBluetooth central manager is
/// created **before** iOS delivers the `willRestoreState` callback.
///
/// When iOS terminates the app and later relaunches it in the background
/// for a Bluetooth event (e.g. a previously-connected camera comes back
/// in range), it expects the app to recreate the `CBCentralManager` with
/// the same restore identifier within ≈ 10 seconds. By calling
/// `ensureInitialized()` here we guarantee this happens on every launch
/// path – foreground and background alike.
/*
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        IosBluetoothController.shared.ensureInitialized()
        return true
    }
}

*/
