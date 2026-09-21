package com.saschl.cameragps.notification

import android.app.Notification
import android.app.NotificationManager
import android.media.RingtoneManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransmissionNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun transmissionUsesAnAlertingChannelWhileInitialStandbyRemainsQuiet() {
        NotificationsHelper.createNotificationChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val waiting = NotificationsHelper.buildWaitingNotification(context)
        val transmitting = NotificationsHelper.buildNotification(context, 1)
        assertNotEquals(waiting.channelId, transmitting.channelId)

        val waitingChannel = manager.getNotificationChannel(waiting.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, waitingChannel.importance)
        assertNull(waitingChannel.sound)
        val transmissionChannel = manager.getNotificationChannel(transmitting.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, transmissionChannel.importance)
        assertEquals(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            transmissionChannel.sound,
        )
        // The alert replaces an existing waiting notification. ONLY_ALERT_ONCE
        // would silence that first transmission because it is an update.
        assertEquals(0, transmitting.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertNotEquals(0, transmitting.flags and Notification.FLAG_ONGOING_EVENT)
    }
}
