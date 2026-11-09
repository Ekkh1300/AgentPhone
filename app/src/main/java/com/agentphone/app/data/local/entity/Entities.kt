package com.agentphone.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val tag: String? = null,
    val systemPrompt: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val providerId: String? = null,
    val model: String? = null,
)

data class ChatSummaryRow(
    @Embedded val chat: ChatEntity,
    @ColumnInfo(name = "preview") val preview: String?,
    @ColumnInfo(name = "message_count") val messageCount: Int,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId"), Index(value = ["chatId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val createdAt: Long,
    val status: String,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolWarning: String? = null,
    val artifactUri: String? = null,
    val artifactName: String? = null,
    val artifactMime: String? = null,
    val imageUrisJson: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val durationMs: Long? = null,
    val error: String? = null,
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val apiKeyEncrypted: String,
    val defaultModel: String,
    val apiFormat: String = "openai",
    val customHeadersJson: String = "{}",
    val organization: String = "",
    val project: String = "",
    val contextWindow: Int = 32_768,
    val inputPricePerMillion: Double = 0.0,
    val outputPricePerMillion: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "auto_replies",
    indices = [Index("address"), Index("receivedAt"), Index("status")],
)
data class AutoReplyEntity(
    @PrimaryKey val id: String,
    val address: String,
    val contactName: String? = null,
    val incomingBody: String,
    val receivedAt: Long,
    val dueAt: Long,
    val status: String,
    val replyText: String? = null,
    val sentAt: Long? = null,
    val reason: String? = null,
)

object AwayChatMode {
    const val DEFAULT = "DEFAULT"
    const val AUTO = "AUTO"
    const val DRAFT = "DRAFT"
    const val NEVER = "NEVER"
}

object AwayChatType {
    const val PRIVATE = "PRIVATE"
    const val GROUP = "GROUP"
    const val CHANNEL = "CHANNEL"
    const val BOT = "BOT"
}

object AwayPendingStatus {
    const val PENDING = "PENDING"
    const val CANCELLED = "CANCELLED"
    const val DONE = "DONE"
}

object AwayDecisionValue {
    const val REPLY = "reply"
    const val DRAFT = "draft"
    const val IGNORE = "ignore"
    const val ESCALATE = "escalate"
}

object AwayDecisionStatus {
    const val SENT = "SENT"
    const val DRAFT = "DRAFT"
    const val IGNORED = "IGNORED"
    const val ESCALATED = "ESCALATED"
    const val FAILED = "FAILED"
    const val REJECTED = "REJECTED"
    const val UNDONE = "UNDONE"
    const val CANCELLED = "CANCELLED"
}

object AwayUrgency {
    const val LOW = "low"
    const val NORMAL = "normal"
    const val HIGH = "high"
}

@Entity(tableName = "away_chat_rules", indices = [Index("updatedAt")])
data class AwayChatRuleEntity(
    @PrimaryKey val chatId: Long,
    val title: String,
    val chatType: String,
    val mode: String,
    val disclosure: Boolean? = null,
    val sendToAi: Boolean = true,
    val instructions: String? = null,
    val addedAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "away_pending",
    indices = [Index("chatId"), Index("dueAt"), Index("status")],
)
data class AwayPendingEntity(
    @PrimaryKey val id: String,
    val chatId: Long,
    val chatTitle: String,
    val chatType: String,
    val senderId: Long,
    val senderName: String,
    val messageIds: String,
    val lastMessageId: Long,
    val firstMessageAt: Long,
    val lastMessageAt: Long,
    val dueAt: Long,
    val status: String,
    val createdAt: Long,
)

@Entity(
    tableName = "away_decisions",
    indices = [Index("chatId"), Index("createdAt"), Index("status"), Index("triggerMessageId")],
)
data class AwayDecisionEntity(
    @PrimaryKey val id: String,
    val chatId: Long,
    val chatTitle: String,
    val chatType: String,
    val senderId: Long,
    val senderName: String,
    val incomingText: String,
    val decision: String,
    val replyText: String? = null,
    val confidence: Double = 0.0,
    val reason: String? = null,
    val urgency: String = AwayUrgency.NORMAL,
    val risksJson: String = "[]",
    val status: String,
    val disclosure: Boolean = false,
    val triggerMessageId: Long,
    val replyToMessageId: Long? = null,
    val sentMessageId: Long? = null,
    val createdAt: Long,
    val sentAt: Long? = null,
    val error: String? = null,
)

@Entity(tableName = "away_watchlist")
data class AwayWatchEntity(
    @PrimaryKey val chatId: Long,
    val title: String,
    val reason: String,
    val addedAt: Long,
)

@Entity(
    tableName = "tool_call_logs",
    indices = [Index("chatId"), Index("startedAt")],
)
data class ToolCallLogEntity(
    @PrimaryKey val id: String,
    val chatId: String?,
    val toolName: String,
    val argumentsJson: String,
    val resultPreview: String,
    val risk: String,
    val status: String,
    val startedAt: Long,
    val durationMs: Long,
    val approval: String,
    val injectionWarning: Boolean,
)
