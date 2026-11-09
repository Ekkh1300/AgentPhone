package com.agentphone.app.telegram

import android.content.Context
import com.agentphone.app.R
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.entity.AwayChatMode
import com.agentphone.app.data.local.entity.AwayChatRuleEntity
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.data.local.entity.AwayDecisionStatus
import com.agentphone.app.data.local.entity.AwayDecisionValue
import com.agentphone.app.data.local.entity.AwayPendingEntity
import com.agentphone.app.data.local.entity.AwayPendingStatus
import com.agentphone.app.data.local.entity.AwayUrgency
import com.agentphone.app.data.local.entity.AwayWatchEntity
import com.agentphone.app.data.repository.AuditRepository
import com.agentphone.app.data.repository.ToolLogStatus
import com.agentphone.app.domain.agent.PromptInjectionDetector
import com.agentphone.app.domain.agent.RiskLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

sealed interface AwayOutcome {
    data object Done : AwayOutcome
    data object Cancelled : AwayOutcome
    data class Retry(val delayMs: Long) : AwayOutcome
    data class Superseded(val chat: ChannelChat, val message: ChannelMessage) : AwayOutcome
}

/**
 * Runs exactly one pending away-mode batch. Every guard the specification calls "hard" lives here:
 * re-checking unread state, allow-list modes, addressed-only groups, rate limits, injection and
 * OTP/payment escalation, disclosure, and the no-tools model call.
 */
@Singleton
class AwayEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val awaySettings: AwaySettingsRepository,
    private val dao: AwayDao,
    private val channel: MessageChannel,
    private val responder: AwayResponder,
    private val scheduler: AwayScheduler,
    private val injectionDetector: PromptInjectionDetector,
    private val auditRepository: AuditRepository,
    private val calendar: CalendarAvailability,
) {

    suspend fun process(pendingId: String): AwayOutcome {
        val pending = dao.pending(pendingId) ?: return AwayOutcome.Cancelled
        if (pending.status != AwayPendingStatus.PENDING) return AwayOutcome.Cancelled
        val away = awaySettings.current()
        if (!away.enabled) return cancel(pending)

        if (!away.insideSchedule()) {
            val wait = scheduler.scheduleAtWindowStart(pending, away)
            scheduler.reschedule(pending, wait)
            return AwayOutcome.Retry(wait)
        }

        val rule = dao.rule(pending.chatId) ?: return cancel(pending)
        if (rule.mode == AwayChatMode.NEVER) return cancel(pending)

        val conversation = channel.conversation(pending.chatId, HISTORY_LIMIT)
            ?: return AwayOutcome.Retry(TimeUnit.MINUTES.toMillis(2))
        val chat = conversation.chat
        if (chat.isBot || chat.isChannel) return cancel(pending)

        val trigger = conversation.messages.lastOrNull { it.id == pending.lastMessageId }
            ?: return cancel(pending)
        if (conversation.lastReadInboxId >= pending.lastMessageId) return cancel(pending)
        if (conversation.messages.any { it.outgoing && it.id > trigger.id }) return cancel(pending)
        val newestIncoming = conversation.messages.lastOrNull { !it.outgoing && !it.isBot }
        if (newestIncoming != null && newestIncoming.id > trigger.id) {
            return AwayOutcome.Superseded(chat, newestIncoming)
        }
        if (chat.isGroup && !trigger.addressedToMe) return cancel(pending)
        if (dao.decisionForMessage(chat.id, trigger.id) != null) return cancel(pending)

        val limitReason = checkLimits(chat, away)
        if (limitReason != null) {
            insertDecision(
                pending, chat, trigger,
                decision = AwayDecisionValue.IGNORE,
                replyText = null,
                confidence = 1.0,
                reason = limitReason,
                urgency = AwayUrgency.LOW,
                risks = emptyList(),
                status = AwayDecisionStatus.IGNORED,
                disclosure = false,
                replyToMessageId = null,
                sentMessageId = null,
                sentAt = null,
                error = null,
            )
            return cancel(pending)
        }

        if (!rule.sendToAi) {
            val reason = context.getString(R.string.away_escalated_ai_disabled)
            val entity = insertDecision(
                pending, chat, trigger,
                decision = AwayDecisionValue.ESCALATE,
                replyText = null,
                confidence = 1.0,
                reason = reason,
                urgency = AwayUrgency.HIGH,
                risks = listOf("send_to_ai_disabled"),
                status = AwayDecisionStatus.ESCALATED,
                disclosure = false,
                replyToMessageId = null,
                sentMessageId = null,
                sentAt = null,
                error = null,
            )
            AwayNotifications.notifyEscalation(context, entity, reason)
            return cancel(pending)
        }

        val injection = injectionDetector.detect(trigger.text)
        val localRisks = AwaySafety.scanIncoming(trigger.text).toMutableSet()
        if (injection != null) {
            localRisks += AwaySafety.Risk.INJECTION
            dao.watch(
                AwayWatchEntity(
                    chatId = chat.id,
                    title = chat.title,
                    reason = "Possible prompt injection",
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
        if (injection != null || AwaySafety.requiresEscalation(localRisks)) {
            val reason = buildString {
                if (injection != null) append("Possible prompt injection. ")
                append("Sensitive content: ")
                append(localRisks.joinToString(", ").ifBlank { "unknown" })
                append('.')
            }
            val entity = insertDecision(
                pending, chat, trigger,
                decision = AwayDecisionValue.ESCALATE,
                replyText = null,
                confidence = 1.0,
                reason = reason,
                urgency = AwayUrgency.HIGH,
                risks = localRisks.toList(),
                status = AwayDecisionStatus.ESCALATED,
                disclosure = false,
                replyToMessageId = null,
                sentMessageId = null,
                sentAt = null,
                error = null,
            )
            AwayNotifications.notifyEscalation(context, entity, reason)
            recordAudit(pending, chat, trigger, "Escalated without an automatic reply: $reason",
                ToolLogStatus.SUCCESS, injection != null)
            return cancel(pending)
        }

        val forcedDraft = forcedDraft(chat, rule, trigger)
        val freeBusy = runCatching { calendar.nextDaySummary() }.getOrNull()
        val startedAt = System.currentTimeMillis()
        val decision = try {
            responder.decide(
                chat = chat,
                context = conversation.messages.takeLast(HISTORY_LIMIT),
                trigger = trigger,
                rule = rule,
                localRisks = localRisks,
                forcedDraft = forcedDraft,
                freeBusy = freeBusy,
            )
        } catch (error: Throwable) {
            val reason = error.message?.take(240) ?: "The AI provider failed."
            awaySettings.recordFailure(reason)
            insertDecision(
                pending, chat, trigger,
                decision = AwayDecisionValue.ESCALATE,
                replyText = null,
                confidence = 0.0,
                reason = "Could not get a decision from the AI provider: $reason",
                urgency = AwayUrgency.HIGH,
                risks = listOf("provider_error"),
                status = AwayDecisionStatus.FAILED,
                disclosure = false,
                replyToMessageId = null,
                sentMessageId = null,
                sentAt = null,
                error = reason,
            )
            AwayNotifications.notifyFailure(
                context,
                context.getString(R.string.away_provider_failed_title),
                context.getString(R.string.away_provider_failed_body, reason),
            )
            recordAudit(pending, chat, trigger, "Provider failure, nothing was sent: $reason",
                ToolLogStatus.ERROR, false, System.currentTimeMillis() - startedAt)
            if (!awaySettings.current().enabled) {
                scheduler.cancelAllPending()
                AwayNotifications.notifyPaused(
                    context,
                    awaySettings.current().disabledReason ?: reason,
                )
            }
            return cancel(pending)
        }

        awaySettings.recordSuccess()
        val replyRisks = AwaySafety.scanReply(decision.replyText.orEmpty())
        val allRisks = (decision.risks + localRisks + replyRisks).distinct()
        val cleaned = decision.replyText?.let { AwaySafety.cleanReply(it, MAX_REPLY_CHARS) }
            ?.takeIf { it.isNotBlank() }
        val mustDraft = forcedDraft || rule.mode == AwayChatMode.DRAFT

        return when {
            AwaySafety.requiresEscalation(allRisks.toSet()) -> {
                val reason = "Sensitive content detected: " + allRisks.joinToString(", ")
                val entity = insertDecision(
                    pending, chat, trigger,
                    decision = AwayDecisionValue.ESCALATE,
                    replyText = cleaned,
                    confidence = decision.confidence,
                    reason = "$reason. Model reason: ${decision.reason}",
                    urgency = AwayUrgency.HIGH,
                    risks = allRisks,
                    status = AwayDecisionStatus.ESCALATED,
                    disclosure = shouldDisclose(rule, away, chat),
                    replyToMessageId = trigger.id.takeIf { chat.isGroup },
                    sentMessageId = null,
                    sentAt = null,
                    error = null,
                )
                AwayNotifications.notifyEscalation(context, entity, reason)
                recordAudit(pending, chat, trigger, "Escalated: $reason", ToolLogStatus.SUCCESS,
                    false, System.currentTimeMillis() - startedAt)
                cancel(pending)
            }

            decision.isIgnore -> {
                insertDecision(
                    pending, chat, trigger,
                    decision = AwayDecisionValue.IGNORE,
                    replyText = null,
                    confidence = decision.confidence,
                    reason = decision.reason,
                    urgency = decision.urgency,
                    risks = allRisks,
                    status = AwayDecisionStatus.IGNORED,
                    disclosure = false,
                    replyToMessageId = null,
                    sentMessageId = null,
                    sentAt = null,
                    error = null,
                )
                cancel(pending)
            }

            decision.isReply && !mustDraft &&
                decision.confidence >= away.confidenceThreshold && replyRisks.isEmpty() ->
                sendNow(pending, chat, trigger, rule, away, decision, cleaned, allRisks, startedAt)

            else -> {
                val status = if (decision.isEscalate) {
                    AwayDecisionStatus.ESCALATED
                } else {
                    AwayDecisionStatus.DRAFT
                }
                val reason = when {
                    decision.isEscalate -> decision.reason
                    mustDraft && !chat.canSend -> "Sending is not allowed here; saved as a draft."
                    mustDraft -> "Saved for approval by the chat rule."
                    decision.confidence < away.confidenceThreshold ->
                        "Confidence is below the configured threshold."
                    else -> decision.reason
                }
                val entity = insertDecision(
                    pending, chat, trigger,
                    decision = if (status == AwayDecisionStatus.ESCALATED) {
                        AwayDecisionValue.ESCALATE
                    } else {
                        AwayDecisionValue.DRAFT
                    },
                    replyText = cleaned,
                    confidence = decision.confidence,
                    reason = reason,
                    urgency = decision.urgency,
                    risks = allRisks,
                    status = status,
                    disclosure = shouldDisclose(rule, away, chat),
                    replyToMessageId = trigger.id.takeIf { chat.isGroup },
                    sentMessageId = null,
                    sentAt = null,
                    error = null,
                )
                if (status == AwayDecisionStatus.ESCALATED) {
                    AwayNotifications.notifyEscalation(context, entity, reason)
                } else {
                    AwayNotifications.notifyDraft(context, entity)
                }
                recordAudit(pending, chat, trigger, "Draft saved: $reason", ToolLogStatus.SUCCESS,
                    false, System.currentTimeMillis() - startedAt)
                cancel(pending)
            }
        }
    }

    private suspend fun sendNow(
        pending: AwayPendingEntity,
        chat: ChannelChat,
        trigger: ChannelMessage,
        rule: AwayChatRuleEntity,
        away: AwaySettings,
        decision: AwayDecision,
        cleaned: String?,
        risks: List<String>,
        startedAt: Long,
    ): AwayOutcome {
        val text = cleaned ?: return cancel(pending)
        val disclose = shouldDisclose(rule, away, chat)
        val finalText = AwaySafety.applyDisclosure(
            text = text,
            enabled = disclose,
            configured = away.disclosureText,
            persian = trigger.text.any { it in '\u0600'..'\u06FF' },
        )
        runCatching { channel.typing(chat.id, trigger.forumTopicId) }
        if (chat.canSend) delay(Random.nextLong(1_500, 3_800))

        val replyTo = trigger.id.takeIf { chat.isGroup }
        val result = channel.send(chat.id, finalText, replyTo, trigger.forumTopicId)
        if (result.ok) {
            val entity = insertDecision(
                pending, chat, trigger,
                decision = AwayDecisionValue.REPLY,
                replyText = finalText,
                confidence = decision.confidence,
                reason = decision.reason,
                urgency = decision.urgency,
                risks = risks,
                status = AwayDecisionStatus.SENT,
                disclosure = disclose,
                replyToMessageId = replyTo,
                sentMessageId = result.messageId,
                sentAt = System.currentTimeMillis(),
                error = null,
            )
            AwayNotifications.notifySent(context, entity)
            recordAudit(pending, chat, trigger, "Sent: $finalText", ToolLogStatus.SUCCESS,
                false, System.currentTimeMillis() - startedAt)
            dao.updatePendingStatus(pending.id, AwayPendingStatus.DONE)
            return AwayOutcome.Done
        }

        val floodWait = result.floodWaitSeconds
        if (floodWait != null && floodWait <= MAX_FLOOD_WAIT_SECONDS) {
            val wait = TimeUnit.SECONDS.toMillis(floodWait.toLong()) + 2_000
            scheduler.reschedule(pending, wait)
            return AwayOutcome.Retry(wait)
        }
        val reason = when {
            result.forbidden -> "Telegram did not allow sending in this chat; saved as a draft."
            floodWait != null -> "Telegram rate limit is too long; saved as a draft."
            else -> result.error ?: "Sending failed."
        }
        val entity = insertDecision(
            pending, chat, trigger,
            decision = AwayDecisionValue.DRAFT,
            replyText = finalText,
            confidence = decision.confidence,
            reason = reason,
            urgency = decision.urgency,
            risks = risks,
            status = AwayDecisionStatus.DRAFT,
            disclosure = disclose,
            replyToMessageId = replyTo,
            sentMessageId = null,
            sentAt = null,
            error = result.error,
        )
        AwayNotifications.notifyDraft(context, entity)
        recordAudit(pending, chat, trigger, "Not sent: $reason", ToolLogStatus.ERROR, false,
            System.currentTimeMillis() - startedAt)
        if (!result.forbidden && floodWait == null) awaySettings.recordFailure(reason)
        return cancel(pending)
    }

    private fun forcedDraft(chat: ChannelChat, rule: AwayChatRuleEntity, trigger: ChannelMessage): Boolean =
        !chat.canSend ||
            rule.mode == AwayChatMode.DRAFT ||
            !trigger.safeForAutoReply ||
            (chat.isPrivate && !trigger.senderIsContact)

    private fun shouldDisclose(rule: AwayChatRuleEntity, away: AwaySettings, chat: ChannelChat): Boolean =
        if (chat.isGroup) rule.disclosure ?: true else rule.disclosure ?: away.disclosureEnabled

    private suspend fun checkLimits(chat: ChannelChat, away: AwaySettings): String? {
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val cap = if (chat.isGroup) (away.perChatDailyCap / 2).coerceAtLeast(1) else away.perChatDailyCap
        val cooldownMinutes = if (chat.isGroup) {
            (away.cooldownMinutes * 2).coerceAtLeast(60)
        } else {
            away.cooldownMinutes
        }
        val sentForChat = dao.sentForChatSince(chat.id, startOfDay)
        val sentGlobal = dao.sentSince(startOfDay)
        val lastSent = dao.lastSentAt(chat.id)
        val sinceLast = lastSent?.let { System.currentTimeMillis() - it }
        return when {
            sentForChat >= cap -> "Daily reply limit for this chat is reached ($cap)."
            sentGlobal >= away.globalDailyCap ->
                "Daily reply limit for all chats is reached (${away.globalDailyCap})."
            sinceLast != null && sinceLast < TimeUnit.MINUTES.toMillis(cooldownMinutes.toLong()) ->
                "The per-chat cooldown of $cooldownMinutes minutes is still active."
            chat.slowModeSeconds > 0 && sinceLast != null && sinceLast < chat.slowModeSeconds * 1_000L ->
                "The group's slow mode is still active."
            else -> null
        }
    }

    private suspend fun insertDecision(
        pending: AwayPendingEntity,
        chat: ChannelChat,
        trigger: ChannelMessage,
        decision: String,
        replyText: String?,
        confidence: Double,
        reason: String,
        urgency: String,
        risks: List<String>,
        status: String,
        disclosure: Boolean,
        replyToMessageId: Long?,
        sentMessageId: Long?,
        sentAt: Long?,
        error: String?,
    ): AwayDecisionEntity {
        val entity = AwayDecisionEntity(
            id = UUID.randomUUID().toString(),
            chatId = chat.id,
            chatTitle = chat.title,
            chatType = chat.kind,
            senderId = trigger.senderId,
            senderName = trigger.senderName,
            incomingText = trigger.text.take(4_000),
            decision = decision,
            replyText = replyText,
            confidence = confidence,
            reason = reason.take(600),
            urgency = urgency,
            risksJson = JsonArray(risks.distinct().map { JsonPrimitive(it) }).toString(),
            status = status,
            disclosure = disclosure,
            triggerMessageId = trigger.id,
            replyToMessageId = replyToMessageId,
            sentMessageId = sentMessageId,
            createdAt = System.currentTimeMillis(),
            sentAt = sentAt,
            error = error,
        )
        dao.insertDecision(entity)
        return entity
    }

    private suspend fun cancel(pending: AwayPendingEntity): AwayOutcome {
        dao.updatePendingStatus(pending.id, AwayPendingStatus.CANCELLED)
        return AwayOutcome.Cancelled
    }

    private suspend fun recordAudit(
        pending: AwayPendingEntity,
        chat: ChannelChat,
        trigger: ChannelMessage,
        result: String,
        status: ToolLogStatus,
        injection: Boolean,
        durationMs: Long = 0,
    ) {
        auditRepository.record(
            chatId = null,
            toolName = "away_reply",
            argumentsJson = buildJsonObject {
                put("chatId", chat.id)
                put("chatTitle", chat.title.take(120))
                put("sender", trigger.senderName.take(120))
                put("messageId", trigger.id)
            }.toString(),
            result = result,
            risk = RiskLevel.DANGEROUS,
            status = status,
            startedAt = System.currentTimeMillis() - durationMs,
            durationMs = durationMs,
            approval = "away-mode",
            injectionWarning = injection,
        )
    }

    companion object {
        const val HISTORY_LIMIT = 14
        const val MAX_REPLY_CHARS = 400
        private const val MAX_FLOOD_WAIT_SECONDS = 900
    }
}
