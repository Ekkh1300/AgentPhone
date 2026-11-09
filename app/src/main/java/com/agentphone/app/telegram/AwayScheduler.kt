package com.agentphone.app.telegram

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.entity.AwayChatMode
import com.agentphone.app.data.local.entity.AwayDecisionStatus
import com.agentphone.app.data.local.entity.AwayDecisionValue
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.data.local.entity.AwayPendingEntity
import com.agentphone.app.data.local.entity.AwayPendingStatus
import com.agentphone.app.data.local.entity.AwayUrgency
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-chat debounce. Every incoming message cancels the previous job for that chat and restarts
 * the timer, so a burst of messages is answered once, timed from the last one.
 */
@Singleton
class AwayScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AwaySettingsRepository,
    private val dao: AwayDao,
) {

    suspend fun onIncoming(chat: ChannelChat, message: ChannelMessage) {
        if (message.outgoing || message.isBot) return
        if (chat.isBot || chat.isChannel) return
        val away = settings.current()
        if (!away.enabled) return
        val rule = dao.rule(chat.id) ?: return
        if (rule.mode == AwayChatMode.NEVER) return

        val now = System.currentTimeMillis()
        // A message that is already a day old is not worth an automatic answer, even unread.
        if (now - message.date > TimeUnit.HOURS.toMillis(STALE_AFTER_HOURS)) return

        // Group chats are never answered unless the owner is addressed directly.
        if (chat.isGroup && !message.addressedToMe) {
            logIgnored(chat, message, "Not addressed to the owner.")
            return
        }
        if (chat.isGroup && message.text.isBlank()) {
            logIgnored(chat, message, "Non-text group message.")
            return
        }

        val delayMs = TimeUnit.MINUTES.toMillis(away.delayMinutes.toLong())
        val dueAt = maxOf(now, message.date + delayMs)
        // Merge a burst into one batch: the timer always restarts from the newest message.
        val previous = dao.pendingFor(chat.id)
        val existing = previous.firstOrNull()
        previous.drop(1).forEach { duplicate ->
            dao.updatePendingStatus(duplicate.id, AwayPendingStatus.CANCELLED)
        }
        val pending = if (existing != null) {
            existing.copy(
                senderId = message.senderId,
                senderName = message.senderName,
                messageIds = (existing.messageIds.split(',')
                    .mapNotNull { it.trim().toLongOrNull() } + message.id)
                    .distinct()
                    .joinToString(","),
                lastMessageId = message.id,
                lastMessageAt = message.date,
                dueAt = dueAt,
            )
        } else {
            AwayPendingEntity(
                id = UUID.randomUUID().toString(),
                chatId = chat.id,
                chatTitle = chat.title,
                chatType = chat.kind,
                senderId = message.senderId,
                senderName = message.senderName,
                messageIds = message.id.toString(),
                lastMessageId = message.id,
                firstMessageAt = message.date,
                lastMessageAt = message.date,
                dueAt = dueAt,
                status = AwayPendingStatus.PENDING,
                createdAt = now,
            )
        }
        dao.upsertPending(pending)
        enqueue(pending.id, chat.id, dueAt, ExistingWorkPolicy.REPLACE)
        dao.deletePendingOlderThan(now - TimeUnit.DAYS.toMillis(30))
    }

    suspend fun cancelForChat(chatId: Long) {
        dao.pendingFor(chatId).forEach {
            dao.updatePendingStatus(it.id, AwayPendingStatus.CANCELLED)
        }
        cancelWork(chatId)
    }

    suspend fun cancelAllPending() {
        dao.allPending().forEach {
            dao.updatePendingStatus(it.id, AwayPendingStatus.CANCELLED)
            cancelWork(it.chatId)
        }
    }

    /** Re-enqueues the same pending batch after a FLOOD_WAIT or a closed schedule window. */
    suspend fun reschedule(pending: AwayPendingEntity, delayMs: Long) {
        val dueAt = System.currentTimeMillis() + delayMs.coerceAtLeast(1_000)
        dao.updatePendingDue(pending.id, dueAt)
        enqueue(pending.id, pending.chatId, dueAt, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /** Called on service start so nothing is lost if WorkManager forgot a job. */
    suspend fun recoverPending() {
        val now = System.currentTimeMillis()
        dao.allPending().forEach { pending ->
            if (pending.dueAt <= now + 1_000) {
                enqueue(pending.id, pending.chatId, now)
            } else {
                enqueue(pending.id, pending.chatId, pending.dueAt)
            }
        }
    }

    fun scheduleAtWindowStart(pending: AwayPendingEntity, away: AwaySettings): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var candidate = now.toLocalDate()
        repeat(8) {
            if (away.dayEnabled(candidate.dayOfWeek)) {
                val start = candidate.atTime(
                    LocalTime.of(
                        (away.scheduleStartMinute / 60).coerceIn(0, 23),
                        (away.scheduleStartMinute % 60).coerceIn(0, 59),
                    ),
                )
                if (start.isAfter(now)) {
                    return java.time.Duration.between(now, start).toMillis()
                }
            }
            candidate = candidate.plusDays(1)
        }
        return TimeUnit.HOURS.toMillis(24)
    }

    private suspend fun logIgnored(chat: ChannelChat, message: ChannelMessage, reason: String) {
        dao.insertDecision(
            AwayDecisionEntity(
                id = UUID.randomUUID().toString(),
                chatId = chat.id,
                chatTitle = chat.title,
                chatType = chat.kind,
                senderId = message.senderId,
                senderName = message.senderName,
                incomingText = message.text.take(2_000),
                decision = AwayDecisionValue.IGNORE,
                replyText = null,
                confidence = 1.0,
                reason = reason,
                urgency = AwayUrgency.LOW,
                risksJson = "[]",
                status = AwayDecisionStatus.IGNORED,
                disclosure = false,
                triggerMessageId = message.id,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun enqueue(
        pendingId: String,
        chatId: Long,
        dueAt: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val delay = (dueAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<AwayWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    AwayWorker.KEY_PENDING to pendingId,
                    AwayWorker.KEY_CHAT to chatId,
                ),
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(chatId),
            policy,
            request,
        )
    }

    private fun cancelWork(chatId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(chatId))
    }

    private fun uniqueName(chatId: Long): String = "${TAG}_$chatId"

    companion object {
        const val TAG = "away_reply"
        private const val STALE_AFTER_HOURS = 24L
    }
}
