package com.agentphone.app.telegram

import android.content.Context
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.entity.AwayChatMode
import com.agentphone.app.data.local.entity.AwayChatRuleEntity
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.data.local.entity.AwayDecisionStatus
import com.agentphone.app.data.repository.AuditRepository
import com.agentphone.app.data.repository.ToolLogStatus
import com.agentphone.app.domain.agent.RiskLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

sealed interface AwayApprovalResult {
    data object Sent : AwayApprovalResult
    data class Failed(val reason: String) : AwayApprovalResult
}

/**
 * Every human action on a draft or escalation. Sending here is always an explicit tap by the owner,
 * so it is audited separately from automatic sends.
 */
@Singleton
class AwayApprover @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AwayDao,
    private val channel: MessageChannel,
    private val awaySettings: AwaySettingsRepository,
    private val scheduler: AwayScheduler,
    private val audit: AuditRepository,
) {

    suspend fun approve(decisionId: String): AwayApprovalResult {
        val decision = dao.decision(decisionId)
            ?: return AwayApprovalResult.Failed("This draft no longer exists.")
        if (decision.status != AwayDecisionStatus.DRAFT &&
            decision.status != AwayDecisionStatus.ESCALATED
        ) {
            return AwayApprovalResult.Failed("This item has already been handled.")
        }
        val text = decision.replyText?.trim().orEmpty()
        if (text.isBlank()) {
            return AwayApprovalResult.Failed("There is no reply text to send.")
        }
        val chat = channel.chat(decision.chatId)
            ?: return AwayApprovalResult.Failed("This chat is not available in Telegram.")
        if (chat.isBot || chat.isChannel) {
            return AwayApprovalResult.Failed("This chat cannot be answered.")
        }
        val away = awaySettings.current()
        val persian = decision.incomingText.any { it in '\u0600'..'\u06FF' }
        val finalText = AwaySafety.applyDisclosure(
            text = AwaySafety.cleanReply(text, 1_500),
            enabled = decision.disclosure,
            configured = away.disclosureText,
            persian = persian,
        )
        val topic = channel.message(chat.id, decision.triggerMessageId)?.forumTopicId
        runCatching { channel.typing(chat.id, topic) }
        delay(Random.nextLong(1_000, 2_800))

        val result = channel.send(
            chatId = chat.id,
            text = finalText,
            replyToMessageId = decision.replyToMessageId ?: decision.triggerMessageId.takeIf { chat.isGroup },
            forumTopicId = topic,
        )
        if (!result.ok) {
            val reason = result.error ?: "Telegram did not accept the message."
            record(decision, "Approved send failed", ToolLogStatus.ERROR)
            return AwayApprovalResult.Failed(reason)
        }
        val sent = decision.copy(
            replyText = finalText,
            sentMessageId = result.messageId,
            sentAt = System.currentTimeMillis(),
        )
        dao.markDecisionSent(
            id = decision.id,
            status = AwayDecisionStatus.SENT,
            replyText = finalText,
            sentMessageId = result.messageId,
            sentAt = sent.sentAt,
            error = null,
        )
        AwayNotifications.cancel(context, decision.id)
        AwayNotifications.notifySent(context, sent)
        record(decision, "Approved by the owner and sent", ToolLogStatus.SUCCESS)
        return AwayApprovalResult.Sent
    }

    /** Rejecting a pending draft: nothing is sent, the row stays for the activity log. */
    suspend fun reject(decisionId: String) {
        dao.finishDecision(decisionId, AwayDecisionStatus.REJECTED, null)
        AwayNotifications.cancel(context, decisionId)
        dao.decision(decisionId)?.let { record(it, "Draft rejected by the owner", ToolLogStatus.CANCELLED) }
    }

    /**
     * Edits a draft, or corrects an already sent message for everyone. Telegram only allows edits of
     * the owner's own messages, which is exactly what an away reply is.
     */
    suspend fun correct(decisionId: String, newText: String): Boolean {
        val trimmed = newText.trim()
        if (trimmed.isBlank()) return false
        val decision = dao.decision(decisionId) ?: return false
        if (decision.status == AwayDecisionStatus.SENT) {
            val messageId = decision.sentMessageId ?: return false
            val edited = channel.editForEveryone(decision.chatId, messageId, trimmed)
            if (!edited) return false
            dao.updateDecisionText(decisionId, trimmed)
            record(decision, "Sent message corrected by the owner", ToolLogStatus.SUCCESS)
            return true
        }
        if (decision.status != AwayDecisionStatus.DRAFT) return false
        dao.updateDecisionText(decisionId, trimmed)
        record(decision, "Draft edited by the owner", ToolLogStatus.SUCCESS)
        return true
    }

    /** Undo removes an away reply for everyone. Drafts are simply closed without sending. */
    suspend fun undo(decisionId: String): Boolean {
        val decision = dao.decision(decisionId) ?: return false
        return when (decision.status) {
            AwayDecisionStatus.SENT -> {
                val messageId = decision.sentMessageId ?: return false
                if (!channel.deleteForEveryone(decision.chatId, messageId)) return false
                dao.updateDecisionStatus(decisionId, AwayDecisionStatus.UNDONE, null)
                record(decision, "Sent away reply was deleted for everyone", ToolLogStatus.SUCCESS)
                true
            }
            AwayDecisionStatus.DRAFT, AwayDecisionStatus.ESCALATED -> {
                dao.finishDecision(decisionId, AwayDecisionStatus.REJECTED, null)
                AwayNotifications.cancel(context, decisionId)
                record(decision, "Draft closed without sending", ToolLogStatus.CANCELLED)
                true
            }
            else -> false
        }
    }

    /** Adds the chat to the allow-list with mode NEVER and cancels everything pending for it. */
    suspend fun neverReply(chatId: Long, title: String, chatType: String) {
        val now = System.currentTimeMillis()
        val existing = dao.rule(chatId)
        dao.upsertRule(
            existing?.copy(
                mode = AwayChatMode.NEVER,
                title = title.ifBlank { existing.title },
                updatedAt = now,
            ) ?: AwayChatRuleEntity(
                chatId = chatId,
                title = title,
                chatType = chatType,
                mode = AwayChatMode.NEVER,
                disclosure = null,
                sendToAi = true,
                instructions = null,
                addedAt = now,
                updatedAt = now,
            ),
        )
        scheduler.cancelForChat(chatId)
        audit.record(
            chatId = null,
            toolName = "away_rule",
            argumentsJson = buildJsonObject {
                put("chatId", chatId)
                put("mode", AwayChatMode.NEVER)
            }.toString(),
            result = "Never auto-reply was set for this chat.",
            risk = RiskLevel.WRITE,
            status = ToolLogStatus.SUCCESS,
            startedAt = now,
            durationMs = 0,
            approval = "user-approval",
            injectionWarning = false,
        )
    }

    private suspend fun record(decision: AwayDecisionEntity, result: String, status: ToolLogStatus) {
        audit.record(
            chatId = null,
            toolName = "away_approval",
            argumentsJson = buildJsonObject {
                put("decisionId", decision.id)
                put("chatId", decision.chatId)
                put("kind", decision.decision)
            }.toString(),
            result = result,
            risk = RiskLevel.DANGEROUS,
            status = status,
            startedAt = System.currentTimeMillis(),
            durationMs = 0,
            approval = "user-approval",
            injectionWarning = false,
        )
    }
}
