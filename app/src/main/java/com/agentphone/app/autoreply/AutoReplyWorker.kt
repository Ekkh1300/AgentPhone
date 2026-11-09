package com.agentphone.app.autoreply

import android.Manifest
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentphone.app.R
import com.agentphone.app.data.local.dao.AutoReplyDao
import com.agentphone.app.data.local.entity.AutoReplyEntity
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.repository.AuditRepository
import com.agentphone.app.data.repository.ToolLogStatus
import com.agentphone.app.domain.agent.PromptInjectionDetector
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.platform.Notifications
import com.agentphone.app.sms.SmsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

@HiltWorker
class AutoReplyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val dao: AutoReplyDao,
    private val composer: AutoReplyComposer,
    private val sms: SmsRepository,
    private val scheduler: AutoReplyScheduler,
    private val audit: AuditRepository,
    private val injectionDetector: PromptInjectionDetector,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val entry = dao.get(id) ?: return Result.success()
        if (entry.status != AutoReplyStatus.PENDING) return Result.success()

        val snapshot = settings.current()
        if (!snapshot.autoReplyEnabled) {
            dao.updateStatus(id, AutoReplyStatus.CANCELLED, "Auto-reply was turned off.")
            return Result.success()
        }
        if (!sms.hasPermission(Manifest.permission.SEND_SMS) ||
            !sms.hasPermission(Manifest.permission.READ_SMS)
        ) {
            dao.updateStatus(id, AutoReplyStatus.SKIPPED, "SMS permissions are missing.")
            notifySkip(entry, R.string.auto_reply_skipped_permission)
            return Result.success()
        }
        if (sms.isOwnNumber(entry.address)) {
            dao.updateStatus(id, AutoReplyStatus.SKIPPED, "The sender is this phone's own number.")
            return Result.success()
        }
        if (sms.hasOutgoingAfter(entry.address, entry.receivedAt)) {
            dao.updateStatus(id, AutoReplyStatus.USER_REPLIED, "The user replied first.")
            return Result.success()
        }

        val newer = sms.latestIncomingAfter(entry.address, entry.receivedAt)
        if (newer != null && !newer.body.isBlank()) {
            val alreadyQueued = dao.pendingFor(entry.address).any {
                it.id != entry.id && it.receivedAt >= newer.date
            }
            if (!alreadyQueued) {
                scheduler.onIncoming(entry.address, newer.body, newer.date)
            }
            dao.updateStatus(id, AutoReplyStatus.SUPERSEDED, "A newer message arrived.")
            return Result.success()
        }

        if (injectionDetector.detect(entry.incomingBody) != null) {
            dao.updateStatus(id, AutoReplyStatus.SKIPPED, "Possible prompt injection in the message.")
            notifySkip(entry, R.string.auto_reply_skipped_suspicious)
            return Result.success()
        }
        if (VERIFICATION_CODE.containsMatchIn(entry.incomingBody)) {
            dao.updateStatus(id, AutoReplyStatus.SKIPPED, "Verification or one-time code.")
            notifySkip(entry, R.string.auto_reply_skipped_code)
            return Result.success()
        }
        val sentToday = dao.sentSince(startOfToday())
        if (sentToday >= snapshot.autoReplyMaxPerDay) {
            dao.updateStatus(id, AutoReplyStatus.SKIPPED, "Daily auto-reply limit reached.")
            notifySkip(entry, R.string.auto_reply_skipped_limit)
            return Result.success()
        }

        val startedAt = System.currentTimeMillis()
        val reply = runCatching {
            composer.compose(entry.address, entry.contactName, entry.incomingBody)
        }.getOrElse { error ->
            if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            dao.updateStatus(id, AutoReplyStatus.FAILED, error.message?.take(300))
            audit.record(
                chatId = null,
                toolName = "auto_reply",
                argumentsJson = argumentsFor(entry),
                result = "Could not compose an auto-reply: ${error.message}",
                risk = RiskLevel.DANGEROUS,
                status = ToolLogStatus.ERROR,
                startedAt = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                approval = "auto-reply-enabled",
                injectionWarning = false,
            )
            Notifications.show(
                applicationContext,
                entry.id.hashCode(),
                applicationContext.getString(R.string.auto_reply_failed_title),
                applicationContext.getString(
                    R.string.auto_reply_failed_body,
                    entry.label(),
                    error.message.orEmpty().take(160),
                ),
                Notifications.CHANNEL_AUTO_REPLY,
            )
            return Result.success()
        }

        return runCatching {
            sms.send(entry.address, reply)
            dao.markSent(id, reply, System.currentTimeMillis())
            audit.record(
                chatId = null,
                toolName = "auto_reply",
                argumentsJson = argumentsFor(entry),
                result = "Sent to ${entry.address}: $reply",
                risk = RiskLevel.DANGEROUS,
                status = ToolLogStatus.SUCCESS,
                startedAt = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                approval = "auto-reply-enabled",
                injectionWarning = false,
            )
            Notifications.show(
                applicationContext,
                entry.id.hashCode(),
                applicationContext.getString(R.string.auto_reply_sent_title),
                applicationContext.getString(R.string.auto_reply_sent_body, entry.label(), reply),
                Notifications.CHANNEL_AUTO_REPLY,
            )
            Result.success()
        }.getOrElse { error ->
            dao.updateStatus(id, AutoReplyStatus.FAILED, error.message?.take(300))
            audit.record(
                chatId = null,
                toolName = "auto_reply",
                argumentsJson = argumentsFor(entry),
                result = "Could not send the auto-reply: ${error.message}",
                risk = RiskLevel.DANGEROUS,
                status = ToolLogStatus.ERROR,
                startedAt = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                approval = "auto-reply-enabled",
                injectionWarning = false,
            )
            Notifications.show(
                applicationContext,
                entry.id.hashCode(),
                applicationContext.getString(R.string.auto_reply_failed_title),
                applicationContext.getString(R.string.auto_reply_failed_body, entry.label(), reply.take(160)),
                Notifications.CHANNEL_AUTO_REPLY,
            )
            Result.success()
        }
    }

    private fun notifySkip(entry: AutoReplyEntity, messageRes: Int) {
        Notifications.show(
            applicationContext,
            entry.id.hashCode(),
            applicationContext.getString(R.string.auto_reply_skipped_title),
            applicationContext.getString(messageRes, entry.label()),
            Notifications.CHANNEL_AUTO_REPLY,
        )
    }

    private fun AutoReplyEntity.label(): String = contactName ?: address

    private fun argumentsFor(entry: AutoReplyEntity): String = buildJsonObject {
        put("address", entry.address)
        put("receivedAt", entry.receivedAt)
        put("message", entry.incomingBody.take(500))
    }.toString()

    private fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        const val KEY_ID = "auto_reply_id"
        private const val MAX_ATTEMPTS = 2
        private val VERIFICATION_CODE = Regex(
            """(?i)\b(otp|one[\s-]?time (?:password|code|pin)|verification code|security code|""" +
                """confirmation code|login code|authentication code|2fa)\b|""" +
                """(?:رمز|کد)\s*(?:یک\s*بار|ورود|تایید|تأیید|امنیتی|فعال\s*سازی)""",
        )
    }
}
