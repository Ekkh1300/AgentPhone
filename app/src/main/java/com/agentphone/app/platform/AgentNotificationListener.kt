package com.agentphone.app.platform

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Optional notification access. The system only binds this service after the user
 * enables AgentPhone in Settings > Notification access.
 */
class AgentNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun snapshot(limit: Int = 40): List<NotificationSnapshot> =
        runCatching {
            activeNotifications.orEmpty()
                .map { it.toSnapshot() }
                .sortedByDescending { it.postedAt }
                .take(limit)
        }.getOrDefault(emptyList())

    fun dismiss(key: String?): Int {
        val current = runCatching { activeNotifications }.getOrNull() ?: return 0
        val targets = if (key.isNullOrBlank()) current.toList() else current.filter { it.key == key }
        targets.forEach { runCatching { cancelNotification(it.key) } }
        return targets.size
    }

    fun open(key: String): Boolean {
        val target = runCatching { activeNotifications }
            .getOrNull()
            ?.firstOrNull { it.key == key } ?: return false
        val pending = target.notification.contentIntent ?: return false
        return runCatching { pending.send(); true }.getOrDefault(false)
    }

    companion object {
        @Volatile
        var instance: AgentNotificationListener? = null
            private set

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
    }
}

data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val ongoing: Boolean,
    val category: String?,
)

fun StatusBarNotification.toSnapshot(): NotificationSnapshot {
    val extras = notification.extras
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = sequenceOf(
        extras.getCharSequence(Notification.EXTRA_TEXT),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
    ).mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        .distinct()
        .joinToString(" — ")
    return NotificationSnapshot(
        key = key,
        packageName = packageName,
        title = title.take(200),
        text = text.take(600),
        postedAt = postTime,
        ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        category = notification.category,
    )
}
