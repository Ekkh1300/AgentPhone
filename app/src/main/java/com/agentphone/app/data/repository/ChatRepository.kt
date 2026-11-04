package com.agentphone.app.data.repository

import com.agentphone.app.data.local.dao.ChatDao
import com.agentphone.app.data.local.dao.MessageDao
import com.agentphone.app.data.local.entity.ChatEntity
import com.agentphone.app.data.local.entity.ChatSummaryRow
import com.agentphone.app.data.local.entity.MessageEntity
import com.agentphone.app.domain.model.AppSettings
import com.agentphone.app.domain.model.Chat
import com.agentphone.app.domain.model.ChatMessage
import com.agentphone.app.domain.model.ChatSummary
import com.agentphone.app.domain.model.MessageRole
import com.agentphone.app.domain.model.MessageStatus
import com.agentphone.app.domain.model.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val json: Json,
) {

    fun observeChats(query: String = ""): Flow<List<ChatSummary>> =
        chatDao.observeSummaries(escapeLikeQuery(query.trim())).map { rows -> rows.map(::toSummary) }

    fun observeChat(chatId: String): Flow<Chat?> =
        chatDao.observeChat(chatId).map { it?.toDomain() }

    fun observeMessages(chatId: String): Flow<List<ChatMessage>> =
        messageDao.observeForChat(chatId).map { list -> list.map { it.toDomain() } }

    suspend fun getChat(chatId: String): Chat? = chatDao.getChat(chatId)?.toDomain()

    suspend fun getMessages(chatId: String): List<ChatMessage> =
        messageDao.getForChat(chatId).map { it.toDomain() }

    suspend fun createChat(
        title: String,
        settings: AppSettings,
        providerId: String? = null,
        model: String? = null,
    ): Chat {
        val now = System.currentTimeMillis()
        val chat = Chat(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { Chat.UNTITLED },
            createdAt = now,
            updatedAt = now,
            providerId = providerId ?: settings.activeProviderId,
            model = model,
            systemPrompt = settings.systemPrompt,
            temperature = settings.temperature,
            maxTokens = settings.maxOutputTokens,
            topP = settings.topP,
        )
        chatDao.upsert(chat.toEntity())
        return chat
    }

    suspend fun updateChat(chat: Chat) {
        chatDao.upsert(chat.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    suspend fun renameChat(chatId: String, title: String) {
        if (title.isBlank()) return
        chatDao.rename(chatId, title.trim().take(120))
    }

    suspend fun setPinned(chatId: String, pinned: Boolean) =
        chatDao.setPinned(chatId, pinned)

    suspend fun setTag(chatId: String, tag: String?) =
        chatDao.setTag(chatId, tag?.trim()?.take(40)?.ifBlank { null })

    suspend fun touch(chatId: String) =
        chatDao.touch(chatId, System.currentTimeMillis())

    suspend fun deleteChat(chatId: String) = chatDao.delete(chatId)

    suspend fun addMessage(message: ChatMessage): ChatMessage {
        messageDao.upsert(message.toEntity())
        chatDao.touch(message.chatId, System.currentTimeMillis())
        return message
    }

    suspend fun updateMessage(message: ChatMessage) {
        messageDao.upsert(message.toEntity())
    }

    suspend fun updateContent(messageId: String, content: String) {
        messageDao.updateContent(messageId, content)
    }

    suspend fun finalizeMessage(
        id: String,
        status: MessageStatus,
        content: String,
        reasoning: String?,
        toolCalls: List<ToolCall>,
        usage: com.agentphone.app.domain.model.TokenUsage?,
        error: String? = null,
    ) {
        messageDao.finalize(
            id = id,
            status = status.name,
            content = content,
            reasoning = reasoning,
            toolCallsJson = if (toolCalls.isEmpty()) null else encodeToolCalls(toolCalls),
            inputTokens = usage?.promptTokens,
            outputTokens = usage?.completionTokens,
            error = error,
        )
    }

    suspend fun deleteMessage(id: String) = messageDao.delete(id)

    suspend fun deleteMessagesAfter(chatId: String, messageId: String) {
        val all = messageDao.getForChat(chatId)
        val index = all.indexOfFirst { it.id == messageId }
        if (index == -1) return
        all.drop(index + 1).forEach { messageDao.delete(it.id) }
    }

    /** Replaces a user message and removes every later turn, for Edit & resend. */
    suspend fun editUserMessage(chatId: String, messageId: String, content: String) {
        val target = messageDao.get(messageId) ?: return
        if (target.role != MessageRole.USER.wire) return
        messageDao.upsert(target.copy(content = content))
        deleteMessagesAfter(chatId, messageId)
        chatDao.touch(chatId, System.currentTimeMillis())
    }

    suspend fun clearMessages(chatId: String) {
        messageDao.deleteForChat(chatId)
        chatDao.touch(chatId, System.currentTimeMillis())
    }

    suspend fun setGeneratedTitle(chatId: String, title: String) {
        val cleaned = title.trim().trim('"').replace(Regex("\\s+"), " ").take(80)
        if (cleaned.isNotEmpty()) chatDao.rename(chatId, cleaned)
    }

    suspend fun allChats(): List<Chat> = chatDao.getAllChats().map { it.toDomain() }

    suspend fun deleteAllChats() = chatDao.deleteAll()

    suspend fun importChat(chat: Chat, messages: List<ChatMessage>) {
        chatDao.upsert(chat.toEntity())
        messageDao.upsertAll(messages.map { it.copy(chatId = chat.id).toEntity() })
    }

    suspend fun exportMarkdown(chatId: String): String {
        val chat = getChat(chatId) ?: return ""
        val messages = getMessages(chatId)
        return buildString {
            append("# ").append(chat.title).append("\n\n")
            append("_Exported from AgentPhone on ")
                .append(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .format(java.time.LocalDateTime.now()))
                .append("_\n\n")
            messages.forEach { message ->
                when (message.role) {
                    MessageRole.USER -> append("## You\n\n")
                    MessageRole.ASSISTANT -> append("## Assistant\n\n")
                    MessageRole.TOOL -> append("### Tool · ${message.toolName ?: "tool"}\n\n")
                    MessageRole.SYSTEM -> append("## System\n\n")
                }
                append(message.content.trim()).append("\n\n")
                if (message.role == MessageRole.ASSISTANT && message.toolCalls.isNotEmpty()) {
                    append("_Tool calls: ")
                        .append(message.toolCalls.joinToString { it.name })
                        .append("_\n\n")
                }
            }
        }
    }

    suspend fun exportJson(chatId: String): String {
        val chat = getChat(chatId) ?: return "{}"
        val messages = getMessages(chatId)
        val root = buildJsonObject {
            put("format", "agentphone.chat.v1")
            put("exportedAt", System.currentTimeMillis())
            putJsonObject("chat") {
                put("id", chat.id)
                put("title", chat.title)
                put("createdAt", chat.createdAt)
                put("updatedAt", chat.updatedAt)
                put("pinned", chat.pinned)
                put("tag", chat.tag)
                put("systemPrompt", chat.systemPrompt)
                put("temperature", chat.temperature)
                put("maxTokens", chat.maxTokens)
                put("topP", chat.topP)
                put("providerId", chat.providerId)
                put("model", chat.model)
            }
            putJsonArray("messages") {
                messages.forEach { message ->
                    addJsonObject {
                        put("id", message.id)
                        put("role", message.role.wire)
                        put("content", message.content)
                        put("reasoning", message.reasoning)
                        put("createdAt", message.createdAt)
                        put("status", message.status.name)
                        put("toolCallId", message.toolCallId)
                        put("toolName", message.toolName)
                        put("toolArgs", message.toolArgs)
                        put("imageUris", buildJsonArray { message.imageUris.forEach { add(it) } })
                    }
                }
            }
        }
        return root.toString()
    }

    private fun escapeLikeQuery(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun toSummary(row: ChatSummaryRow): ChatSummary {
        val preview = row.preview.orEmpty()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(160)
        return ChatSummary(row.chat.toDomain(), preview, row.messageCount)
    }

    private fun encodeToolCalls(calls: List<ToolCall>): String = buildJsonArray {
        calls.forEach { call ->
            addJsonObject {
                put("id", call.id)
                put("type", "function")
                putJsonObject("function") {
                    put("name", call.name)
                    put("arguments", call.arguments.ifBlank { "{}" })
                }
            }
        }
    }.toString()

    private fun decodeToolCalls(raw: String?): List<ToolCall> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val fn = obj["function"] as? JsonObject ?: return@mapNotNull null
                val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                ToolCall(
                    id = (obj["id"] as? JsonPrimitive)?.contentOrNull
                        ?: UUID.randomUUID().toString(),
                    name = name,
                    arguments = (fn["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}",
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeUris(uris: List<String>): String? =
        if (uris.isEmpty()) null else buildJsonArray { uris.forEach { add(it) } }.toString()

    private fun decodeUris(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun ChatEntity.toDomain(): Chat = Chat(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        tag = tag,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        providerId = providerId,
        model = model,
    )

    private fun Chat.toEntity(): ChatEntity = ChatEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        tag = tag,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        providerId = providerId,
        model = model,
    )

    private fun MessageEntity.toDomain(): ChatMessage = ChatMessage(
        id = id,
        chatId = chatId,
        role = MessageRole.fromWire(role),
        content = content,
        reasoning = reasoning,
        createdAt = createdAt,
        status = MessageStatus.from(status),
        toolCalls = decodeToolCalls(toolCallsJson),
        toolCallId = toolCallId,
        toolName = toolName,
        toolArgs = toolArgsJson,
        toolWarning = toolWarning,
        artifactUri = artifactUri,
        artifactName = artifactName,
        artifactMime = artifactMime,
        imageUris = decodeUris(imageUrisJson),
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        durationMs = durationMs,
        error = error,
    )

    private fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
        id = id,
        chatId = chatId,
        role = role.wire,
        content = content,
        reasoning = reasoning,
        createdAt = createdAt,
        status = status.name,
        toolCallsJson = if (toolCalls.isEmpty()) null else encodeToolCalls(toolCalls),
        toolCallId = toolCallId,
        toolName = toolName,
        toolArgsJson = toolArgs,
        toolWarning = toolWarning,
        artifactUri = artifactUri,
        artifactName = artifactName,
        artifactMime = artifactMime,
        imageUrisJson = encodeUris(imageUris),
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        durationMs = durationMs,
        error = error,
    )
}
