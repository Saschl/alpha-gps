package com.sasch.cameragps.sharednew

import androidx.compose.ui.window.ComposeUIViewController
import com.sasch.cameragps.sharednew.ui.theme.CameraGpsTheme
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
        CameraGpsTheme {
            CameraGpsIosApp()
        }
}
