package com.saschl.cameragps.notification

import android.app.Notification
import android.app.NotificationManager
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.locationTransmissionNotificationId
import com.saschl.cameragps.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransmissionNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun blockedDisconnectAlertsStillUpdateCountsAndRestoreWaiting() = runBlocking {
        val updates = mutableListOf<Pair<Int, Notification>>()
        val publisher = AndroidTransmissionNotificationPublisher(
            context,
            isChannelEnabled = { it != NotificationsHelper.DISCONNECT_NOTIFICATION_CHANNEL },
        ) { id, notification ->
            updates += id to notification
        }

        publisher.showIdle()
        publisher.show(1)
        publisher.show(2)
        publisher.show(1)
        publisher.showIdle()
        publisher.show(1)

        assertEquals(List(6) { locationTransmissionNotificationId }, updates.map { it.first })
        val notifications = updates.map { it.second }
        assertEquals(
            listOf(
                NotificationsHelper.NOTIFICATION_CHANNEL_ID,
                NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL,
                NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL,
                NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL,
                NotificationsHelper.NOTIFICATION_CHANNEL_ID,
                NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL,
            ),
            notifications.map { it.channelId },
        )
        assertEquals(
            listOf(
                context.getString(R.string.app_standby_content),
                context.getString(R.string.foreground_service_notification, 1),
                context.getString(R.string.foreground_service_notification, 2),
                context.getString(R.string.foreground_service_notification, 1),
                context.getString(R.string.app_standby_content),
                context.getString(R.string.foreground_service_notification, 1),
            ),
            notifications.map { it.extras.getString(Notification.EXTRA_TEXT) },
        )
        // Count decreases are quiet; connecting another camera or reconnecting can alert again.
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, notifications[3].groupAlertBehavior)
        for (index in listOf(1, 2, 5)) {
            assertEquals(
                NotificationCompat.GROUP_ALERT_ALL,
                notifications[index].groupAlertBehavior
            )
            assertEquals(0, notifications[index].flags and Notification.FLAG_ONLY_ALERT_ONCE)
        }
    }

    @Test
    fun enabledDisconnectAlertsKeepTheirConfiguredChannel() = runBlocking {
        val updates = mutableListOf<Notification>()
        val publisher = AndroidTransmissionNotificationPublisher(
            context,
            isChannelEnabled = { true },
        ) { _, notification -> updates += notification }

        publisher.show(2)
        publisher.show(1)
        publisher.showIdle()

        assertEquals(NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL, updates[0].channelId)
        for (notification in updates.drop(1)) {
            assertEquals(
                NotificationsHelper.DISCONNECT_NOTIFICATION_CHANNEL,
                notification.channelId
            )
            assertEquals(NotificationCompat.GROUP_ALERT_ALL, notification.groupAlertBehavior)
        }
        assertEquals(
            context.getString(R.string.app_standby_content),
            updates.last().extras.getString(Notification.EXTRA_TEXT),
        )
    }

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
