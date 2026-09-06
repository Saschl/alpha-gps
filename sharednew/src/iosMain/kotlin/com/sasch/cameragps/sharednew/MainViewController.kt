package com.sasch.cameragps.sharednew

import androidx.compose.ui.window.ComposeUIViewController
import com.sasch.cameragps.sharednew.ui.theme.CameraGpsTheme
import platform.UIKit.UIViewController

/**
 * Entry point for the iOS host application.
 *
 * The Swift host supplies the StoreKit request callback and embeds the returned
 * controller. The callback returns whether it could issue a request, not whether
 * Apple displayed the prompt or the user left a review.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(requestReview: (UIViewController) -> Boolean): UIViewController =
    ComposeUIViewController {
        CameraGpsTheme {
            CameraGpsIosApp(requestReview = requestReview)
        }
}
