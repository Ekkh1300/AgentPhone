package com.agentphone.app.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.agentphone.app.MainActivity
import com.agentphone.app.R

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_AGENT = "agent"
    const val CHANNEL_BACKGROUND = "background_agent"
    const val CHANNEL_AUTO_REPLY = "auto_reply"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_reminders_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENT,
                context.getString(R.string.notification_channel_agent),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_agent_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BACKGROUND,
                context.getString(R.string.notification_channel_background),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_background_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AUTO_REPLY,
                context.getString(R.string.notification_channel_auto_reply),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_auto_reply_desc)
            },
        )
    }

    fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        channel: String = CHANNEL_AGENT,
    ) {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
