package com.saschl.cameragps.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.saschl.cameragps.MainActivity
import com.saschl.cameragps.R

internal object NotificationsHelper {

    const val NOTIFICATION_CHANNEL_ID = "general_notification_channel"
    const val DISCONNECT_NOTIFICATION_CHANNEL = "disconnect_notification_channel"


    fun createNotificationChannel(context: Context) {
        val notificationManager =
            context.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setSound(null, null)
        channel.enableVibration(false)

        val disconnectChannel = NotificationChannel(
            DISCONNECT_NOTIFICATION_CHANNEL,
            context.getString(R.string.disconnect_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        disconnectChannel.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(disconnectChannel)
    }

    fun showNotification(context: Context, notificationId: Int, notification: Notification) {
        val notificationManager =
            context.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(notificationId, notification)
    }

    fun buildNotification(
        context: Context,
        activeCameras: Int,
        channelId: String = NOTIFICATION_CHANNEL_ID
    ): Notification {
        return NotificationCompat.Builder(context, channelId)
            .setOngoing(true)
            .setContentTitle(
                context.getString(
                    R.string.foreground_service_notification,
                    activeCameras
                )
            )
            .setContentText(
                context.getString(
                    R.string.foreground_service_notification,
                    activeCameras
                )
            )
            .setSmallIcon(R.drawable.ic_gps_fixed)
            .setContentIntent(Intent(context, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    context,
                    0,
                    notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            })
            .build()
    }

    fun buildNotification(
        context: Context,
        title: String,
        content: String,
        channelId: String = DISCONNECT_NOTIFICATION_CHANNEL
    ): Notification {
        // TODO separate channels for standby and connected
        return NotificationCompat.Builder(context, channelId)
            .setOngoing(true)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_gps_fixed)
            .setContentIntent(Intent(context, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    context,
                    0,
                    notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            })
            .build()
    }
}