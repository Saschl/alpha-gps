package com.sasch.cameragps.sharednew

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UIKit.UIApplicationOpenSettingsURLString

/** `canOpenURL` on the shared application — replaced in tests, which have no UIApplication. */
internal val defaultCanOpenUrl: (NSURL) -> Boolean =
    { UIApplication.sharedApplication.canOpenURL(it) }

/**
 * URL for this app's notification page in Settings. iOS can refuse the direct deep link
 * (it was only added in 15.4/16), so fall back to the app's Settings page, from where
 * "Notifications" is one tap away.
 */
internal fun notificationSettingsUrl(
    canOpen: (NSURL) -> Boolean = defaultCanOpenUrl,
): NSURL? {
    val notifications = NSURL.URLWithString(UIApplicationOpenNotificationSettingsURLString)
    if (notifications != null && canOpen(notifications)) return notifications
    val appSettings = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return null
    return appSettings.takeIf(canOpen)
}
