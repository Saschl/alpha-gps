//
//  ContentView.swift
//  iosApp
//
//  Created by Sascha Rudolf on 16.03.26.
//

import SwiftUI
import StoreKit
import sharedKit

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        #if DEBUG && targetEnvironment(simulator)
        if let scenario = ProcessInfo.processInfo.environment["ALPHA_GPS_SCREENSHOT"] {
            return StoreScreenshotViewControllerKt.StoreScreenshotViewController(scenario: scenario)
        }
        #endif
        return MainViewControllerKt.MainViewController(requestReview: { controller in
            guard UIApplication.shared.applicationState == .active,
                  let window = controller.viewIfLoaded?.window,
                  window.isKeyWindow,
                  let scene = window.windowScene,
                  scene.activationState == .foregroundActive else {
                return KotlinBoolean(value: false)
            }
            // AccessorySetupKit and other native sheets can cover the Compose UI.
            var ancestor: UIViewController? = controller
            while let current = ancestor {
                guard current.presentedViewController == nil else {
                    return KotlinBoolean(value: false)
                }
                ancestor = current.parent
            }
            AppStore.requestReview(in: scene)
            // StoreKit provides no display/completion callback. This only means
            // the request was submitted; Apple may choose not to show anything.
            return KotlinBoolean(value: true)
        })
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

#Preview {
    ContentView()
}
