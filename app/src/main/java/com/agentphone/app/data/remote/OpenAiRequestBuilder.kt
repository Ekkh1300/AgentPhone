package com.agentphone.app.data.remote

import com.agentphone.app.domain.provider.LlmMessage
import com.agentphone.app.domain.provider.LlmRequest
import com.agentphone.app.domain.provider.ProviderAdapter
import com.agentphone.app.domain.provider.ToolDefinition
import com.agentphone.app.domain.model.ProviderProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class OpenAiRequestBuilder(
    private val json: Json,
    private val imageEncoder: ImageEncoder,
) {

    fun body(request: LlmRequest, stream: Boolean): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", stream)
        if (stream) {
            putJsonObject("stream_options") {
                put("include_usage", true)
            }
        }
        putJsonArray("messages") {
            request.messages.forEach { add(message(it)) }
        }
        request.temperature?.let { put("temperature", it) }
        request.maxTokens?.takeIf { it > 0 }?.let { put("max_tokens", it) }
        request.topP?.takeIf { it in 0.01f..1f }?.let { put("top_p", it) }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { add(tool(it)) }
            }
            put("tool_choice", "auto")
        }
    }

    fun message(message: LlmMessage): JsonObject = buildJsonObject {
        put("role", message.role)
        if (message.role == "user" && message.imageUris.isNotEmpty()) {
            putJsonArray("content") {
                if (message.content.isNotBlank()) {
                    addJsonObject {
                        put("type", "text")
                        put("text", message.content)
                    }
                }
                message.imageUris.forEach { image ->
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", imageEncoder.encode(image))
                            put("detail", "auto")
                        }
                    }
                }
            }
        } else {
            if (message.content.isEmpty() && message.toolCalls.isNotEmpty()) {
                put("content", JsonNull)
            } else {
                put("content", message.content)
            }
        }
        message.name?.let { put("name", it) }
        message.toolCallId?.let { put("tool_call_id", it) }
        if (message.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                message.toolCalls.forEach { call ->
                    addJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", call.name)
                            put("arguments", call.arguments.ifBlank { "{}" })
                        }
                    }
                }
            }
        }
    }

    private fun tool(tool: ToolDefinition): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
        }
    }
}

internal fun ProviderProfile.customHeaderMap(): Map<String, String> = customHeaders
