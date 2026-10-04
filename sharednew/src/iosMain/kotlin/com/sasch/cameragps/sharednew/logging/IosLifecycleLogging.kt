package com.sasch.cameragps.sharednew.logging

import com.diamondedge.logging.logging
import platform.UIKit.UIApplication
import platform.UIKit.UIScene

private val lifecycleLog = logging("IosLifecycle")

internal fun logIosLifecycle(event: String, scene: UIScene? = null) {
    lifecycleLog.d {
        val app = UIApplication.sharedApplication
        val scenes = app.connectedScenes.filterIsInstance<UIScene>()
            .map { it.activationState }
        "iOS lifecycle: $event; appState=${app.applicationState}; " +
                "sceneStates=$scenes; eventSceneState=${scene?.activationState}"
    }
}
