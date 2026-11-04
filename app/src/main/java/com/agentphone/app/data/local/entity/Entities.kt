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
