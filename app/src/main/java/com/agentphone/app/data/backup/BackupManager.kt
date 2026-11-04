package com.agentphone.app.data.backup

import com.agentphone.app.data.repository.ChatRepository
import com.agentphone.app.domain.model.Chat
import com.agentphone.app.domain.model.ChatMessage
import com.agentphone.app.domain.model.MessageRole
import com.agentphone.app.domain.model.MessageStatus
import com.agentphone.app.domain.model.ToolCall
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    private val chats: ChatRepository,
    private val json: Json,
) {
    suspend fun exportJson(): String {
        val allChats = chats.allChats()
        val chatMessages = allChats.associateWith { chats.getMessages(it.id) }
        val root = buildJsonObject {
            put("format", FORMAT)
            put("exportedAt", System.currentTimeMillis())
            putJsonArray("chats") {
                allChats.forEach { chat ->
                    addJsonObject {
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
                        putJsonArray("messages") {
                            chatMessages[chat].orEmpty().forEach { message ->
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
                                    put("toolWarning", message.toolWarning)
                                    put("artifactUri", message.artifactUri)
                                    put("artifactName", message.artifactName)
                                    put("artifactMime", message.artifactMime)
                                    putJsonArray("imageUris") { message.imageUris.forEach { add(it) } }
                                    putJsonArray("toolCalls") {
                                        message.toolCalls.forEach { call ->
                                            addJsonObject {
                                                put("id", call.id)
                                                put("name", call.name)
                                                put("arguments", call.arguments)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return root.toString()
    }

    /** Returns the number of imported chats. */
    suspend fun importJson(raw: String): Int {
        val root = json.parseToJsonElement(raw).jsonObject
        val version = (root["format"] as? JsonPrimitive)?.contentOrNull
        require(version == FORMAT) { "This is not an AgentPhone backup file." }
        val chatArray = root["chats"] as? JsonArray ?: return 0
        var count = 0
        chatArray.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val now = System.currentTimeMillis()
            val chat = Chat(
                id = id,
                title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: Chat.UNTITLED,
                createdAt = (obj["createdAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: now,
                updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: now,
                pinned = (obj["pinned"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false,
                tag = (obj["tag"] as? JsonPrimitive)?.contentOrNull,
                systemPrompt = (obj["systemPrompt"] as? JsonPrimitive)?.contentOrNull,
                temperature = (obj["temperature"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull(),
                maxTokens = (obj["maxTokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                topP = (obj["topP"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull(),
                providerId = (obj["providerId"] as? JsonPrimitive)?.contentOrNull,
                model = (obj["model"] as? JsonPrimitive)?.contentOrNull,
            )
            val messages = (obj["messages"] as? JsonArray)?.mapNotNull { messageElement ->
                val m = messageElement as? JsonObject ?: return@mapNotNull null
                val messageId = (m["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                ChatMessage(
                    id = messageId,
                    chatId = id,
                    role = MessageRole.fromWire(
                        (m["role"] as? JsonPrimitive)?.contentOrNull ?: "user",
                    ),
                    content = (m["content"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    reasoning = (m["reasoning"] as? JsonPrimitive)?.contentOrNull,
                    createdAt = (m["createdAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: now,
                    status = MessageStatus.from((m["status"] as? JsonPrimitive)?.contentOrNull),
                    toolCalls = (m["toolCalls"] as? JsonArray)?.mapNotNull { callElement ->
                        val c = callElement as? JsonObject ?: return@mapNotNull null
                        ToolCall(
                            id = (c["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                            name = (c["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                            arguments = (c["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}",
                        )
                    }.orEmpty(),
                    toolCallId = (m["toolCallId"] as? JsonPrimitive)?.contentOrNull,
                    toolName = (m["toolName"] as? JsonPrimitive)?.contentOrNull,
                    toolArgs = (m["toolArgs"] as? JsonPrimitive)?.contentOrNull,
                    toolWarning = (m["toolWarning"] as? JsonPrimitive)?.contentOrNull,
                    artifactUri = (m["artifactUri"] as? JsonPrimitive)?.contentOrNull,
                    artifactName = (m["artifactName"] as? JsonPrimitive)?.contentOrNull,
                    artifactMime = (m["artifactMime"] as? JsonPrimitive)?.contentOrNull,
                    imageUris = (m["imageUris"] as? JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    }.orEmpty(),
                )
            }.orEmpty()
            chats.importChat(chat, messages)
            count++
        }
        return count
    }

    private companion object {
        const val FORMAT = "agentphone.backup.v1"
    }
}
