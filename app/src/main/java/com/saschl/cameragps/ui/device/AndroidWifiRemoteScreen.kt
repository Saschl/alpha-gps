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
    var pendingAutomatic by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val host = pendingHost
        val automatic = pendingAutomatic
        pendingHost = null
        pendingAutomatic = false
        if (automatic || host != null) {
            if (grants.values.any { !it }) controller.permissionDenied(identifier)
            else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (automatic) controller.connectAutomatically(identifier) else controller.connect(identifier, host!!)
            }
        }
    }
    val close = { controller.disconnect(identifier); onClose() }
    BackHandler(onBack = close)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) {
            pendingHost = null
            pendingAutomatic = false
            controller.disconnect(identifier)
        }
    }
    DisposableEffect(identifier) {
        onDispose { if (activity?.isChangingConfigurations != true) controller.disconnect(identifier) }
    }
    val connect: (String?) -> Unit = { host ->
        val required = buildList {
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
            if (host == null) {
                if (Build.VERSION.SDK_INT >= 33) add("android.permission.NEARBY_WIFI_DEVICES")
                else {
                    add("android.permission.ACCESS_FINE_LOCATION")
                    add("android.permission.ACCESS_COARSE_LOCATION")
                }
            }
        }
        val missing = required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingHost = host
            pendingAutomatic = host == null
            // Android 12 requires fine and coarse to be requested together.
            permission.launch(required.toTypedArray())
        } else if (host == null) controller.connectAutomatically(identifier) else controller.connect(identifier, host)
    }
    WifiRemoteScreen(model, onConnect = { connect(it) }, onConnectAutomatically = { connect(null) },
        onWifiSettings = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, onClose = close)
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
