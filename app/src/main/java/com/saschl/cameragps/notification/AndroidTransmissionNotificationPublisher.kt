package com.saschl.cameragps.notification

import android.content.Context
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.locationTransmissionNotificationId
import com.sasch.cameragps.sharednew.notification.TransmissionNotificationCoordinator

/** Renders shared transmission state into the service's existing ongoing notification. */
internal class AndroidTransmissionNotificationPublisher(
    private val context: Context,
) : TransmissionNotificationCoordinator.Publisher {
    private var previousCameraCount = 0

    override suspend fun show(cameraCount: Int) = publish(cameraCount)

    // A foreground service must retain its notification even with no transmitting cameras.
    override fun showIdle() = publish(0)

    private fun publish(cameraCount: Int) {
        val channelId = if (cameraCount < previousCameraCount) {
            NotificationsHelper.DISCONNECT_NOTIFICATION_CHANNEL
        } else {
            NotificationsHelper.NOTIFICATION_CHANNEL_ID
        }
        val notification = if (cameraCount == 0) {
            NotificationsHelper.buildWaitingNotification(context, channelId)
        } else {
            NotificationsHelper.buildNotification(context, cameraCount, channelId)
        }
        NotificationsHelper.showNotification(
            context,
            locationTransmissionNotificationId,
            notification
        )
        previousCameraCount = cameraCount
    }
}
