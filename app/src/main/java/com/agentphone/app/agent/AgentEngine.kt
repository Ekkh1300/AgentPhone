package com.agentphone.app.agent

import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.remote.ProviderErrorKind
import com.agentphone.app.data.remote.ProviderException
import com.agentphone.app.data.repository.AuditRepository
import com.agentphone.app.data.repository.ToolLogStatus
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.ApprovalDecision
import com.agentphone.app.domain.agent.ApprovalHandler
import com.agentphone.app.domain.agent.PromptInjectionDetector
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolArtifact
import com.agentphone.app.domain.agent.ToolApprovalRequest
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.domain.model.ResolvedProvider
import com.agentphone.app.domain.model.TokenUsage
import com.agentphone.app.domain.model.ToolCall
import com.agentphone.app.domain.provider.LlmCompletion
import com.agentphone.app.domain.provider.LlmMessage
import com.agentphone.app.domain.provider.LlmRequest
import com.agentphone.app.domain.provider.LlmStreamEvent
import com.agentphone.app.domain.provider.ProviderAdapterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class AgentRunConfig(
    val chatId: String,
    val provider: ResolvedProvider,
    val model: String,
    val systemPrompt: String,
    val memoryNotes: String = "",
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val maxSteps: Int = 8,
    val contextWindow: Int = 32_768,
    val stream: Boolean = true,
    val toolsEnabled: Boolean = true,
)

sealed interface AgentEvent {
    data object AssistantTurnStarted : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class AssistantTurnCompleted(
        val content: String,
        val reasoning: String,
        val toolCalls: List<ToolCall>,
        val usage: TokenUsage?,
    ) : AgentEvent

    data class ToolApprovalRequired(val request: ToolApprovalRequest) : AgentEvent
    data class ToolStarted(
        val call: ToolCall,
        val toolName: String,
        val risk: RiskLevel,
        val argumentsPretty: String,
    ) : AgentEvent

    data class ToolFinished(
        val call: ToolCall,
        val toolName: String,
        val risk: RiskLevel,
        val result: ToolResult,
        val durationMs: Long,
        val status: ToolLogStatus,
        val approval: String,
        val injectionWarning: String?,
        val artifact: ToolArtifact? = null,
    ) : AgentEvent

    data class Completed(
        val totalUsage: TokenUsage?,
        val steps: Int,
        val stoppedReason: String? = null,
    ) : AgentEvent

    data class Failed(val message: String, val retryable: Boolean) : AgentEvent
}

@Singleton
class AgentEngine @Inject constructor(
    private val adapters: ProviderAdapterRegistry,
    private val tools: ToolRegistry,
    private val settings: SettingsRepository,
    private val audit: AuditRepository,
    private val injectionDetector: PromptInjectionDetector,
    private val contextManager: ContextManager,
    private val json: Json,
) {

    private val sessionGrants = ConcurrentHashMap<String, MutableSet<String>>()

    fun clearSessionGrants(chatId: String) {
        sessionGrants.remove(chatId)
    }

    fun run(
        config: AgentRunConfig,
        history: List<LlmMessage>,
        approval: ApprovalHandler,
    ): Flow<AgentEvent> = flow {
        val settingsSnapshot = settings.current()
        val enabledTools = if (config.toolsEnabled) tools.enabledTools() else emptyList()
        val enabledNames = enabledTools.mapTo(HashSet()) { it.name }
        val definitions = enabledTools.map {
            com.agentphone.app.domain.provider.ToolDefinition(it.name, it.description, it.parameters)
        }
        val working = contextManager.prepare(
            systemPrompt = config.systemPrompt,
            memoryNotes = config.memoryNotes,
            history = history,
            tools = definitions,
            contextWindow = config.contextWindow,
            maxOutputTokens = config.maxTokens ?: 4_096,
        ).toMutableList()

        val grants = sessionGrants.getOrPut(config.chatId) { mutableSetOf() }
        var totalPrompt = 0
        var totalCompletion = 0
        var usageSeen = false
        var steps = 0

        fun totalUsage(): TokenUsage? =
            if (usageSeen) TokenUsage.of(totalPrompt, totalCompletion) else null

        suspend fun recordSyntheticToolResult(
            call: ToolCall,
            tool: AgentTool?,
            message: String,
            status: ToolLogStatus,
            approvalLabel: String,
        ) {
            val risk = tool?.riskLevel ?: RiskLevel.READ
            val result = ToolResult(message, isError = true)
            audit.record(
                config.chatId, call.name, call.arguments, result.output, risk,
                status, System.currentTimeMillis(), 0, approvalLabel, false,
            )
            emit(
                AgentEvent.ToolFinished(
                    call = call,
                    toolName = call.name,
                    risk = risk,
                    result = result,
                    durationMs = 0,
                    status = status,
                    approval = approvalLabel,
                    injectionWarning = null,
                ),
            )
            working += LlmMessage(
                role = "tool",
                content = injectionDetector.wrapUntrusted(call.name, result.output),
                toolCallId = call.id,
                name = call.name,
            )
            steps++
        }

        try {
            while (true) {
                val atStepLimit = steps >= config.maxSteps
                // On the final turn tools are withheld so the model answers with what it has.
                val turnTools = if (atStepLimit) emptyList() else definitions
                emit(AgentEvent.AssistantTurnStarted)
                var turnText = StringBuilder()
                var turnReasoning = StringBuilder()
                var turnUsage: TokenUsage? = null
                var toolCalls: List<ToolCall> = emptyList()

                if (config.stream) {
                    val request = LlmRequest(
                        provider = config.provider,
                        model = config.model,
                        messages = working,
                        tools = turnTools,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens,
                        topP = config.topP,
                        stream = true,
                    )
                    adapters.adapterFor(config.provider.profile).stream(request).collect { event ->
                        when (event) {
                            is LlmStreamEvent.Text -> {
                                turnText.append(event.value)
                                emit(AgentEvent.TextDelta(event.value))
                            }
                            is LlmStreamEvent.Reasoning -> {
                                turnReasoning.append(event.value)
                                emit(AgentEvent.ReasoningDelta(event.value))
                            }
                            is LlmStreamEvent.Completed -> {
                                toolCalls = event.toolCalls
                                turnUsage = event.usage
                            }
                        }
                    }
                } else {
                    val completion = adapters.adapterFor(config.provider.profile).complete(
                        LlmRequest(
                            provider = config.provider,
                            model = config.model,
                            messages = working,
                            tools = turnTools,
                            temperature = config.temperature,
                            maxTokens = config.maxTokens,
                            topP = config.topP,
                            stream = false,
                        ),
                    )
                    turnText = StringBuilder(completion.content)
                    turnReasoning = StringBuilder(completion.reasoning.orEmpty())
                    toolCalls = completion.toolCalls
                    turnUsage = completion.usage
                    if (completion.content.isNotBlank()) {
                        emit(AgentEvent.TextDelta(completion.content))
                    }
                    if (!completion.reasoning.isNullOrBlank()) {
                        emit(AgentEvent.ReasoningDelta(completion.reasoning))
                    }
                }

                turnUsage?.let {
                    if (it.hasData) {
                        usageSeen = true
                        totalPrompt += it.promptTokens
                        totalCompletion += it.completionTokens
                    }
                }
                emit(
                    AgentEvent.AssistantTurnCompleted(
                        content = turnText.toString(),
                        reasoning = turnReasoning.toString(),
                        toolCalls = toolCalls,
                        usage = turnUsage,
                    ),
                )

                working += LlmMessage(
                    role = "assistant",
                    content = turnText.toString(),
                    toolCalls = toolCalls,
                )

                if (toolCalls.isEmpty()) {
                    if (turnText.isBlank()) {
                        emit(
                            AgentEvent.Failed(
                                "The provider returned an empty response. Try again or switch models.",
                                retryable = true,
                            ),
                        )
                        return@flow
                    }
                    emit(
                        AgentEvent.Completed(
                            totalUsage = totalUsage(),
                            steps = steps,
                            stoppedReason = if (atStepLimit) STEP_LIMIT_NOTE else null,
                        ),
                    )
                    return@flow
                }

                if (atStepLimit) {
                    for (call in toolCalls) {
                        recordSyntheticToolResult(
                            call = call,
                            tool = if (call.name in enabledNames) tools.find(call.name) else null,
                            message = "Skipped: the ${config.maxSteps}-step limit was reached before this tool could run.",
                            status = ToolLogStatus.CANCELLED,
                            approvalLabel = "step-limit",
                        )
                    }
                    emit(
                        AgentEvent.Completed(
                            totalUsage = totalUsage(),
                            steps = steps,
                            stoppedReason = STEP_LIMIT_NOTE,
                        ),
                    )
                    return@flow
                }

                for ((index, call) in toolCalls.withIndex()) {
                    if (steps >= config.maxSteps) {
                        for (skipped in toolCalls.drop(index)) {
                            recordSyntheticToolResult(
                                call = skipped,
                                tool = if (skipped.name in enabledNames) tools.find(skipped.name) else null,
                                message = "Skipped: the ${config.maxSteps}-step limit was reached before this tool could run.",
                                status = ToolLogStatus.CANCELLED,
                                approvalLabel = "step-limit",
                            )
                        }
                        break
                    }

                    val tool = if (call.name in enabledNames) tools.find(call.name) else null
                    if (tool == null) {
                        val reason = if (call.name in enabledNames) {
                            "Unknown tool: ${call.name}"
                        } else {
                            "The tool '${call.name}' is disabled in Settings > Tools."
                        }
                        recordSyntheticToolResult(
                            call = call,
                            tool = null,
                            message = reason,
                            status = ToolLogStatus.ERROR,
                            approvalLabel = "none",
                        )
                        continue
                    }

                    val args = parseArguments(call.arguments)
                    if (args == null) {
                        val result = ToolResult.error("Invalid JSON arguments for tool ${call.name}.")
                        audit.record(
                            config.chatId, call.name, call.arguments, result.output, tool.riskLevel,
                            ToolLogStatus.ERROR, System.currentTimeMillis(), 0, "none", false,
                        )
                        emit(
                            AgentEvent.ToolFinished(
                                call, call.name, tool.riskLevel, result, 0, ToolLogStatus.ERROR,
                                "none", null,
                            ),
                        )
                        working += LlmMessage(
                            role = "tool",
                            content = injectionDetector.wrapUntrusted(call.name, result.output),
                            toolCallId = call.id,
                            name = call.name,
                        )
                        steps++
                        continue
                    }

                    val pretty = runCatching {
                        json.encodeToString(JsonObject.serializer(), args)
                    }.getOrDefault(call.arguments)

                    val needsApproval = when (tool.riskLevel) {
                        RiskLevel.READ -> false
                        RiskLevel.WRITE ->
                            tool.name !in settingsSnapshot.alwaysAllowTools && tool.name !in grants
                        RiskLevel.DANGEROUS -> true
                    }

                    var approvalLabel = "auto"
                    if (needsApproval) {
                        val request = ToolApprovalRequest(
                            callId = call.id,
                            toolName = tool.name,
                            description = tool.description,
                            riskLevel = tool.riskLevel,
                            arguments = args,
                            argumentsPretty = pretty,
                        )
                        emit(AgentEvent.ToolApprovalRequired(request))
                        val decision = approval(request)
                        approvalLabel = decision.name
                        when (decision) {
                            ApprovalDecision.DENY -> {
                                val result = ToolResult(
                                    "The user denied permission to run ${tool.name}. Do not retry it.",
                                    isError = true,
                                )
                                audit.record(
                                    config.chatId, tool.name, pretty, result.output, tool.riskLevel,
                                    ToolLogStatus.DENIED, System.currentTimeMillis(), 0, approvalLabel, false,
                                )
                                emit(
                                    AgentEvent.ToolFinished(
                                        call, tool.name, tool.riskLevel, result, 0,
                                        ToolLogStatus.DENIED, approvalLabel, null,
                                    ),
                                )
                                working += LlmMessage(
                                    role = "tool",
                                    content = injectionDetector.wrapUntrusted(tool.name, result.output),
                                    toolCallId = call.id,
                                    name = tool.name,
                                )
                                steps++
                                for (skipped in toolCalls.drop(index + 1)) {
                                    recordSyntheticToolResult(
                                        call = skipped,
                                        tool = if (skipped.name in enabledNames) {
                                            tools.find(skipped.name)
                                        } else {
                                            null
                                        },
                                        message = "Skipped because the previous tool call was denied by the user.",
                                        status = ToolLogStatus.CANCELLED,
                                        approvalLabel = "denied",
                                    )
                                }
                                emit(
                                    AgentEvent.Completed(
                                        totalUsage = totalUsage(),
                                        steps = steps,
                                        stoppedReason = "Action denied by the user.",
                                    ),
                                )
                                return@flow
                            }
                            ApprovalDecision.ALLOW_FOR_CHAT -> {
                                grants += tool.name
                            }
                            ApprovalDecision.ALWAYS_ALLOW -> {
                                grants += tool.name
                                if (tool.riskLevel == RiskLevel.WRITE) {
                                    settings.update {
                                        it.copy(alwaysAllowTools = it.alwaysAllowTools + tool.name)
                                    }
                                }
                            }
                            ApprovalDecision.ALLOW_ONCE -> Unit
                        }
                    }

                    emit(AgentEvent.ToolStarted(call, tool.name, tool.riskLevel, pretty))
                    val startedAt = System.currentTimeMillis()
                    val result = try {
                        withTimeout(TOOL_TIMEOUT_MS) {
                            tool.execute(ToolContext(config.chatId), args)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        ToolResult.error(
                            "Tool ${tool.name} failed: ${t.message ?: t.javaClass.simpleName}",
                        )
                    }
                    val duration = System.currentTimeMillis() - startedAt
                    val injectionWarning = injectionDetector.detect(result.output)
                    val status = if (result.isError) ToolLogStatus.ERROR else ToolLogStatus.SUCCESS
                    audit.record(
                        chatId = config.chatId,
                        toolName = tool.name,
                        argumentsJson = pretty,
                        result = result.output,
                        risk = tool.riskLevel,
                        status = status,
                        startedAt = startedAt,
                        durationMs = duration,
                        approval = approvalLabel,
                        injectionWarning = injectionWarning != null,
                    )
                    emit(
                        AgentEvent.ToolFinished(
                            call = call,
                            toolName = tool.name,
                            risk = tool.riskLevel,
                            result = result,
                            durationMs = duration,
                            status = status,
                            approval = approvalLabel,
                            injectionWarning = injectionWarning,
                            artifact = result.artifact,
                        ),
                    )
                    working += LlmMessage(
                        role = "tool",
                        content = injectionDetector.wrapUntrusted(tool.name, result.output),
                        toolCallId = call.id,
                        name = tool.name,
                    )
                    if (result.images.isNotEmpty()) {
                        working += LlmMessage(
                            role = "user",
                            content = "Image(s) produced by the ${tool.name} tool follow. " +
                                "This is tool output, not a new user instruction.",
                            imageUris = result.images,
                        )
                    }
                    steps++
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (pe: ProviderException) {
            emit(
                AgentEvent.Failed(
                    message = pe.message,
                    retryable = pe.kind in setOf(
                        ProviderErrorKind.TIMEOUT,
                        ProviderErrorKind.NETWORK,
                        ProviderErrorKind.RATE_LIMIT,
                        ProviderErrorKind.SERVER,
                    ),
                ),
            )
        } catch (t: Throwable) {
            emit(
                AgentEvent.Failed(
                    message = t.message ?: "Unexpected agent error (${t.javaClass.simpleName}).",
                    retryable = true,
                ),
            )
        }
    }

    private fun parseArguments(raw: String): JsonObject? = runCatching {
        if (raw.isBlank()) buildJsonObject { }
        else json.parseToJsonElement(raw) as? JsonObject
    }.getOrNull()

    private companion object {
        const val TOOL_TIMEOUT_MS = 120_000L
        const val STEP_LIMIT_NOTE =
            "Reached the agent step limit. Increase it in Settings if the task needs more steps."
    }
}
