//
//  ContentView.swift
//  iosApp
//
//  Created by Sascha Rudolf on 16.03.26.
//

import SwiftUI
import sharedKit

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        #if DEBUG && targetEnvironment(simulator)
        if let scenario = ProcessInfo.processInfo.environment["ALPHA_GPS_SCREENSHOT"] {
            return StoreScreenshotViewControllerKt.StoreScreenshotViewController(scenario: scenario)
        }
        #endif
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

#Preview {
    ContentView()
}
