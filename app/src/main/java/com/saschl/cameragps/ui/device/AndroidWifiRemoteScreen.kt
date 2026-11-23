package com.saschl.cameragps.ui.device

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sasch.cameragps.sharednew.remote.wifi.WifiPhotoDownload
import com.sasch.cameragps.sharednew.ui.remote.WifiRemoteScreen
import com.sasch.cameragps.sharednew.ui.remote.WifiRemoteViewModel
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.utils.PreferencesManager

private class PendingPhotoDownloads : ViewModel() {
    var downloads: List<WifiPhotoDownload> = emptyList()
}

@Composable
fun AndroidWifiRemoteScreen(identifier: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = AppServices.from(context).wifiRemote
    val owner by controller.owner.collectAsState()
    KeepScreenOnWhileRemoteActive(owner.equals(identifier, ignoreCase = true))
    val model: WifiRemoteViewModel = viewModel(key = "wifi-remote-${identifier.uppercase()}") {
        WifiRemoteViewModel(identifier.uppercase(), controller)
    }
    var pendingHost by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAutomatic by rememberSaveable { mutableStateOf(false) }
    fun startCameraSetup() {
        if (controller.supportsAutomaticConnection) controller.connectAutomatically(identifier)
        else controller.prepareManualConnection(identifier)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val host = pendingHost
        val automatic = pendingAutomatic
        pendingHost = null
        pendingAutomatic = false
        if (automatic || host != null) {
            if (grants.values.any { !it }) controller.permissionDenied(identifier)
            else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (automatic) startCameraSetup() else controller.connect(identifier, host!!)
            }
        }
    }
    // A large date range can exceed the saved-state Bundle limit during the permission prompt.
    val pendingPhotos = viewModel(key = "wifi-photo-permission-${identifier.uppercase()}") { PendingPhotoDownloads() }
    val storagePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val downloads = pendingPhotos.downloads
            pendingPhotos.downloads = emptyList()
            if (granted && downloads.isNotEmpty() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                controller.downloadPhotos(identifier, downloads)
            }
            else if (!granted) controller.imageStoragePermissionDenied(identifier)
        }
    val close = { controller.disconnect(identifier); onClose() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) {
            pendingHost = null
            pendingAutomatic = false
        }
    }
    // Backgrounding keeps Wi-Fi alive; leaving remote or stopping the FGS closes it.
    DisposableEffect(identifier) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                pendingPhotos.downloads = emptyList()
                controller.disconnect(identifier)
            }
        }
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
        } else if (host == null) startCameraSetup() else controller.connect(identifier, host)
    }
    WifiRemoteScreen(model, onConnect = { connect(it) }, onConnectAutomatically = { connect(null) },
        isExperimentalNoticeAcknowledged = {
            PreferencesManager.isWifiRemoteNoticeAcknowledged(
                context
            )
        },
        onAcknowledgeExperimentalNotice = { PreferencesManager.acknowledgeWifiRemoteNotice(context) },
        onAppSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri()
                )
            )
        },
        onDownloadPhotos = { downloads ->
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingPhotos.downloads = downloads
                storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else controller.downloadPhotos(identifier, downloads)
        },
        onWifiSettings = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, onClose = close)
}

@Composable
internal fun KeepScreenOnWhileRemoteActive(active: Boolean) {
    val view = LocalView.current
    if (active) {
        DisposableEffect(view) {
            val previous = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
