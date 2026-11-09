package com.agentphone.app.telegram

import android.annotation.SuppressLint
import android.app.Notification
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
import com.agentphone.app.data.local.entity.AwayDecisionEntity

/**
 * All away-mode notifications. Drafts and escalations are actionable; a service notification keeps
 * the foreground service honest, and the pause action is always one tap away.
 */
object AwayNotifications {

    const val CHANNEL_SERVICE = "away_service"
    const val CHANNEL_ACTIVITY = "away_activity"
    const val CHANNEL_URGENT = "away_urgent"
    const val CHANNEL_SUMMARY = "away_summary"

    const val ACTION_PAUSE = "com.agentphone.app.away.PAUSE"
    const val ACTION_APPROVE = "com.agentphone.app.away.APPROVE"
    const val ACTION_REJECT = "com.agentphone.app.away.REJECT"
    const val EXTRA_DECISION_ID = "decision_id"

    const val SERVICE_ID = 7101

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.away_channel_service),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.away_channel_service_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACTIVITY,
                context.getString(R.string.away_channel_activity),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.away_channel_activity_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_URGENT,
                context.getString(R.string.away_channel_urgent),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.away_channel_urgent_desc)
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SUMMARY,
                context.getString(R.string.away_channel_summary),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.away_channel_summary_desc)
            },
        )
    }

    fun serviceNotification(context: Context, text: String): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.away_service_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                context.getString(R.string.away_pause),
                pauseIntent(context),
            )
            .setContentIntent(openApp(context, null))
            .build()
    }

    @SuppressLint("MissingPermission")
    fun updateService(context: Context, text: String) {
        if (!canNotify(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(
                SERVICE_ID,
                serviceNotification(context, text),
            )
        }
    }

    fun notifySent(context: Context, decision: AwayDecisionEntity) {
        val body = decision.replyText.orEmpty()
        show(
            context,
            decision.id.hashCode(),
            context.getString(R.string.away_sent_title, decision.senderName.ifBlank { decision.chatTitle }),
            body,
            CHANNEL_ACTIVITY,
        )
    }

    @SuppressLint("MissingPermission")
    fun notifyDraft(context: Context, decision: AwayDecisionEntity) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(
                    R.string.away_draft_title,
                    decision.senderName.ifBlank { decision.chatTitle },
                ),
            )
            .setContentText(decision.replyText.orEmpty().ifBlank {
                context.getString(R.string.away_draft_empty)
            })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    decision.replyText.orEmpty().ifBlank {
                        context.getString(R.string.away_draft_empty)
                    },
                ),
            )
            .setAutoCancel(false)
            .setContentIntent(openApp(context, decision.id))
            .addAction(0, context.getString(R.string.away_approve), actionIntent(context, decision.id, ACTION_APPROVE))
            .addAction(0, context.getString(R.string.away_reject), actionIntent(context, decision.id, ACTION_REJECT))
        runCatching { NotificationManagerCompat.from(context).notify(decision.id.hashCode(), builder.build()) }
    }

    @SuppressLint("MissingPermission")
    fun notifyEscalation(context: Context, decision: AwayDecisionEntity, reason: String) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val text = buildString {
            append(reason)
            if (decision.incomingText.isNotBlank()) {
                append("\n")
                append(decision.incomingText.take(300))
            }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_URGENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(
                    R.string.away_escalation_title,
                    decision.senderName.ifBlank { decision.chatTitle },
                ),
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, decision.id))
            .addAction(0, context.getString(R.string.away_pause), pauseIntent(context))
        runCatching { NotificationManagerCompat.from(context).notify(decision.id.hashCode(), builder.build()) }
    }

    fun notifyPaused(context: Context, reason: String) {
        val text = context.getString(R.string.away_auto_disabled_body, reason.take(180))
        show(context, 7201, context.getString(R.string.away_auto_disabled_title), text, CHANNEL_URGENT)
    }

    fun notifyServiceDown(context: Context) {
        show(
            context,
            7202,
            context.getString(R.string.away_service_down_title),
            context.getString(R.string.away_service_down_body),
            CHANNEL_URGENT,
        )
    }

    fun notifySummary(context: Context, title: String, text: String) {
        show(context, 7203, title, text, CHANNEL_SUMMARY)
    }

    fun notifyFailure(context: Context, title: String, text: String) {
        show(context, title.hashCode(), title, text, CHANNEL_URGENT)
    }

    fun cancel(context: Context, decisionId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(decisionId.hashCode()) }
    }

    @SuppressLint("MissingPermission")
    private fun show(context: Context, id: Int, title: String, text: String, channel: String) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, null))
        if (channel == CHANNEL_URGENT) builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openApp(context: Context, decisionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, if (decisionId != null) "away/drafts" else "away")
            decisionId?.let { putExtra(EXTRA_DECISION_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            (decisionId?.hashCode() ?: 0) + 11,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, decisionId: String, action: String): PendingIntent {
        val intent = Intent(context, AwayActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_DECISION_ID, decisionId)
        }
        return PendingIntent.getBroadcast(
            context,
            (decisionId + action).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pauseIntent(context: Context): PendingIntent {
        val intent = Intent(context, AwayActionReceiver::class.java).apply { action = ACTION_PAUSE }
        return PendingIntent.getBroadcast(
            context,
            99,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
