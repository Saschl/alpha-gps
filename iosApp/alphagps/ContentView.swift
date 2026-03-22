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
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

#Preview {
    ContentView()
}
