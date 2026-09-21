package com.sasch.cameragps.sharednew

import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UIKit.UIApplicationOpenSettingsURLString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosSettingsLinksTest {
    @Test
    fun prefersTheNotificationPageWhenIosAcceptsTheDeepLink() {
        val url = notificationSettingsUrl(canOpen = { true })

        assertEquals(UIApplicationOpenNotificationSettingsURLString, url?.absoluteString)
    }

    @Test
    fun fallsBackToTheAppSettingsPage() {
        val url = notificationSettingsUrl(
            canOpen = { it.absoluteString != UIApplicationOpenNotificationSettingsURLString },
        )

        assertEquals(UIApplicationOpenSettingsURLString, url?.absoluteString)
    }

    @Test
    fun returnsNullWhenNoSettingsUrlCanBeOpened() {
        assertNull(notificationSettingsUrl(canOpen = { false }))
    }
}
