//
//  alphagpsApp.swift
//  iosApp
//
//  Created by Sascha Rudolf on 16.03.26.
//

import SwiftUI

@main
struct alphagpsApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView().ignoresSafeArea()
        }
    }
}
