package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

@Composable
internal actual fun KeepBulbScreenAwake() {
    DisposableEffect(Unit) {
        val app = UIApplication.sharedApplication
        val previous = app.idleTimerDisabled
        app.idleTimerDisabled = true
        onDispose { app.idleTimerDisabled = previous }
    }
}
