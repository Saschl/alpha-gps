import SwiftUI
import CameraGpsShared

/// SwiftUI wrapper that hosts the Compose Multiplatform UI from the shared KMP module.
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

