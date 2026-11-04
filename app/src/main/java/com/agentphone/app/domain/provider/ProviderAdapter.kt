package com.agentphone.app.domain.provider

import com.agentphone.app.domain.model.ModelInfo
import com.agentphone.app.domain.model.ProviderProfile
import com.agentphone.app.domain.model.ResolvedProvider
import com.agentphone.app.domain.model.TokenUsage
import com.agentphone.app.domain.model.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

data class LlmMessage(
    val role: String,
    val content: String = "",
    val imageUris: List<String> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class LlmRequest(
    val provider: ResolvedProvider,
    val model: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val stream: Boolean = true,
)

sealed interface LlmStreamEvent {
    data class Text(val value: String) : LlmStreamEvent
    data class Reasoning(val value: String) : LlmStreamEvent
    data class Completed(
        val finishReason: String?,
        val toolCalls: List<ToolCall>,
        val usage: TokenUsage?,
    ) : LlmStreamEvent
}

data class LlmCompletion(
    val content: String,
    val reasoning: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val usage: TokenUsage?,
)

data class ProviderTestResult(
    val ok: Boolean,
    val message: String,
    val latencyMs: Long = 0,
    val models: List<ModelInfo> = emptyList(),
)

interface ProviderAdapter {
    val apiFormat: String

    fun stream(request: LlmRequest): Flow<LlmStreamEvent>

    suspend fun complete(request: LlmRequest): LlmCompletion

    suspend fun listModels(provider: ResolvedProvider): List<ModelInfo>

    suspend fun test(provider: ResolvedProvider): ProviderTestResult
}

interface ProviderAdapterRegistry {
    fun adapterFor(profile: ProviderProfile): ProviderAdapter
    val supportedFormats: List<String>
    val displayNames: Map<String, String>
}
