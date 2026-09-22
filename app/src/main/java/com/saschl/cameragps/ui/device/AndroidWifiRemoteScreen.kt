package com.saschl.cameragps.ui.device

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sasch.cameragps.sharednew.ui.remote.WifiRemoteScreen
import com.sasch.cameragps.sharednew.ui.remote.WifiRemoteViewModel
import com.saschl.cameragps.AppServices

@Composable
fun AndroidWifiRemoteScreen(identifier: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = AppServices.from(context).wifiRemote
    val model: WifiRemoteViewModel = viewModel(key = "wifi-remote-${identifier.uppercase()}") {
        WifiRemoteViewModel(identifier.uppercase(), controller)
    }
    var pendingHost by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val host = pendingHost
        pendingHost = null
        if (!granted) controller.permissionDenied(identifier)
        else if (host != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.connect(identifier, host)
    }
    val close = { controller.disconnect(identifier); onClose() }
    BackHandler(onBack = close)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) {
            pendingHost = null
            controller.disconnect(identifier)
        }
    }
    DisposableEffect(identifier) {
        onDispose { if (activity?.isChangingConfigurations != true) controller.disconnect(identifier) }
    }
    WifiRemoteScreen(model, onConnect = { host ->
        val localNetwork = "android.permission.ACCESS_LOCAL_NETWORK"
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context, localNetwork) != PackageManager.PERMISSION_GRANTED) {
            pendingHost = host
            permission.launch(localNetwork)
        } else controller.connect(identifier, host)
    }, onWifiSettings = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, onClose = close)
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
