package com.sasch.cameragps.sharednew

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point for the iOS host application.
 *
 * The Xcode project should call:
 *   CameraGpsShared.MainViewControllerKt.MainViewController()
 * and embed the returned UIViewController as the root view controller.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController =
    ComposeUIViewController {
        MaterialTheme {
            CameraGpsIosApp()
        }
}
