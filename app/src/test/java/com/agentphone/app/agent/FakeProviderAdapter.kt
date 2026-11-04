package com.agentphone.app.agent

import com.agentphone.app.domain.model.ModelInfo
import com.agentphone.app.domain.model.ResolvedProvider
import com.agentphone.app.domain.model.TokenUsage
import com.agentphone.app.domain.model.ToolCall
import com.agentphone.app.domain.provider.LlmCompletion
import com.agentphone.app.domain.provider.LlmRequest
import com.agentphone.app.domain.provider.LlmStreamEvent
import com.agentphone.app.domain.provider.ProviderAdapter
import com.agentphone.app.domain.provider.ProviderTestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** In-memory provider used by unit tests and available as a template for previews. */
class FakeProviderAdapter(
    private val turns: List<Turn>,
) : ProviderAdapter {

    data class Turn(
        val text: String = "",
        val reasoning: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
        val usage: TokenUsage? = null,
    )

    var callCount: Int = 0
        private set

    override val apiFormat: String = "fake"

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val turn = turns[callCount.coerceAtMost(turns.lastIndex)]
        callCount++
        if (turn.reasoning.isNotEmpty()) emit(LlmStreamEvent.Reasoning(turn.reasoning))
        if (turn.text.isNotEmpty()) emit(LlmStreamEvent.Text(turn.text))
        emit(
            LlmStreamEvent.Completed(
                finishReason = if (turn.toolCalls.isEmpty()) "stop" else "tool_calls",
                toolCalls = turn.toolCalls,
                usage = turn.usage,
            ),
        )
    }

    override suspend fun complete(request: LlmRequest): LlmCompletion {
        val turn = turns[callCount.coerceAtMost(turns.lastIndex)]
        callCount++
        return LlmCompletion(
            content = turn.text,
            reasoning = turn.reasoning.ifBlank { null },
            toolCalls = turn.toolCalls,
            finishReason = if (turn.toolCalls.isEmpty()) "stop" else "tool_calls",
            usage = turn.usage,
        )
    }

    override suspend fun listModels(provider: ResolvedProvider): List<ModelInfo> =
        listOf(ModelInfo("fake-model"))

    override suspend fun test(provider: ResolvedProvider): ProviderTestResult =
        ProviderTestResult(true, "ok", models = listOf(ModelInfo("fake-model")))
}
