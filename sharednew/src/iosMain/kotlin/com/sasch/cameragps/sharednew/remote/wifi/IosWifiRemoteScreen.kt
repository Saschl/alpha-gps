package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.wifi_remote_ios_settings
import cameragps.sharednew.generated.resources.wifi_remote_ios_opening
import cameragps.sharednew.generated.resources.wifi_remote_ios_permission
import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.ui.remote.WifiRemoteScreen
import com.sasch.cameragps.sharednew.ui.remote.WifiRemoteViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSURL
import platform.UIKit.*

@Composable
internal fun IosWifiRemoteScreen(identifier: String, onClose: () -> Unit) {
    val controller = IosBluetoothController.wifiRemote
    val scope = rememberCoroutineScope()
    val owner by controller.owner.collectAsState()
    val model = viewModel(key = "wifi-remote-${identifier.uppercase()}") {
        WifiRemoteViewModel(identifier.uppercase(), controller)
    }
    DisposableEffect(identifier) {
        onDispose { controller.disconnect(identifier) }
    }
    if (owner.equals(identifier, ignoreCase = true)) {
        DisposableEffect(Unit) {
            val app = UIApplication.sharedApplication
            val previous = app.idleTimerDisabled
            app.idleTimerDisabled = true
            onDispose { app.idleTimerDisabled = previous }
        }
    }
    WifiRemoteScreen(model,
        onConnect = { controller.connect(identifier, it) },
        onConnectAutomatically = { controller.connectAutomatically(identifier) },
        onWifiSettings = {
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null)
            }
        },
        openingSessionMessage = stringResource(Res.string.wifi_remote_ios_opening),
        networkPermissionMessage = stringResource(Res.string.wifi_remote_ios_permission),
        wifiSettingsLabel = stringResource(Res.string.wifi_remote_ios_settings),
        onDownloadPhoto = { handle, format ->
            scope.launch {
                if (requestIosPhotoSavePermission()) {
                    if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground)
                        controller.downloadPhoto(identifier, handle, format)
                } else controller.imageStoragePermissionDenied(identifier)
            }
        },
        onClose = { controller.disconnect(identifier); onClose() },
    )
}
