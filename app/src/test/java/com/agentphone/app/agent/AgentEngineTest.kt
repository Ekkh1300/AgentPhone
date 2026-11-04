package com.agentphone.app.agent

import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.repository.AuditRepository
import com.agentphone.app.data.repository.ToolLogStatus
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.ApprovalDecision
import com.agentphone.app.domain.agent.PromptInjectionDetector
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.domain.model.AppSettings
import com.agentphone.app.domain.model.ProviderProfile
import com.agentphone.app.domain.model.ResolvedProvider
import com.agentphone.app.domain.model.TokenUsage
import com.agentphone.app.domain.model.ToolCall
import com.agentphone.app.domain.provider.LlmMessage
import com.agentphone.app.domain.provider.ProviderAdapter
import com.agentphone.app.domain.provider.ProviderAdapterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEngineTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun executesToolThenReturnsFinalAnswer() = runTest {
        val toolExecutions = AtomicInteger(0)
        val echoTool = EchoTool(riskLevel = RiskLevel.READ) { toolExecutions.incrementAndGet() }
        val provider = FakeProviderAdapter(
            listOf(
                FakeProviderAdapter.Turn(
                    text = "Let me check.",
                    toolCalls = listOf(ToolCall("call_1", "echo", """{"text":"hello"}""")),
                    usage = TokenUsage.of(10, 3),
                ),
                FakeProviderAdapter.Turn(
                    text = "Done: hello",
                    usage = TokenUsage.of(20, 4),
                ),
            ),
        )
        val engine = engine(provider, echoTool, risk = RiskLevel.READ)

        val events = engine.run(
            config = config(),
            history = listOf(LlmMessage("user", "please echo hello")),
            approval = { ApprovalDecision.ALLOW_ONCE },
        ).toList()

        assertEquals(1, toolExecutions.get())
        assertTrue(events.filterIsInstance<AgentEvent.TextDelta>().any { it.text.contains("Done") })
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("echo", finished.toolName)
        assertEquals(ToolLogStatus.SUCCESS, finished.status)
        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(30, completed.totalUsage?.promptTokens)
        assertEquals(7, completed.totalUsage?.completionTokens)
        assertTrue(completed.stoppedReason == null)
    }

    @Test
    fun denialStopsLoopAndDoesNotExecuteTool() = runTest {
        val executions = AtomicInteger(0)
        val echoTool = EchoTool(riskLevel = RiskLevel.WRITE) { executions.incrementAndGet() }
        val provider = FakeProviderAdapter(
            listOf(
                FakeProviderAdapter.Turn(
                    text = "",
                    toolCalls = listOf(ToolCall("call_1", "echo", """{"text":"hello"}""")),
                ),
            ),
        )
        val engine = engine(provider, echoTool, risk = RiskLevel.WRITE)

        val events = engine.run(
            config = config(),
            history = listOf(LlmMessage("user", "write something")),
            approval = { ApprovalDecision.DENY },
        ).toList()

        assertEquals(0, executions.get())
        val denied = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals(ToolLogStatus.DENIED, denied.status)
        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertTrue(completed.stoppedReason?.contains("denied") == true)
    }

    @Test
    fun readToolDoesNotRequestApproval() = runTest {
        val provider = FakeProviderAdapter(
            listOf(
                FakeProviderAdapter.Turn(toolCalls = listOf(ToolCall("1", "echo", "{}"))),
                FakeProviderAdapter.Turn(text = "finished"),
            ),
        )
        val engine = engine(provider, EchoTool(riskLevel = RiskLevel.READ) {}, risk = RiskLevel.READ)
        var approvals = 0
        val events = engine.run(
            config(),
            listOf(LlmMessage("user", "go")),
            approval = {
                approvals++
                ApprovalDecision.ALLOW_ONCE
            },
        ).toList()
        assertEquals(0, approvals)
        assertTrue(events.any { it is AgentEvent.Completed })
    }

    private fun engine(
        provider: ProviderAdapter,
        tool: AgentTool,
        risk: RiskLevel,
    ): AgentEngine {
        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.current() } returns AppSettings(
            enabledTools = setOf(tool.name),
            maxAgentSteps = 5,
        )
        val registry = mockk<ToolRegistry>(relaxed = true)
        coEvery { registry.enabledTools() } returns listOf(tool)
        every { registry.find(tool.name) } returns tool
        val audit = mockk<AuditRepository>(relaxed = true)
        val adapters = object : ProviderAdapterRegistry {
            override fun adapterFor(profile: ProviderProfile): ProviderAdapter = provider
            override val supportedFormats: List<String> = listOf("fake")
            override val displayNames: Map<String, String> = mapOf("fake" to "Fake")
        }
        return AgentEngine(
            adapters = adapters,
            tools = registry,
            settings = settings,
            audit = audit,
            injectionDetector = PromptInjectionDetector(),
            contextManager = ContextManager(),
            json = json,
        )
    }

    private fun config(): AgentRunConfig = AgentRunConfig(
        chatId = "chat-1",
        provider = ResolvedProvider(
            ProviderProfile(
                id = "p1",
                name = "Test",
                baseUrl = "https://example.com/v1",
                defaultModel = "fake-model",
            ),
            apiKey = "test-key",
        ),
        model = "fake-model",
        systemPrompt = "You are a test assistant.",
        maxSteps = 5,
    )

    private class EchoTool(
        override val riskLevel: RiskLevel = RiskLevel.READ,
        private val onExecute: () -> Unit,
    ) : AgentTool {
        override val name = "echo"
        override val description = "Echo text."
        override val parameters = buildJsonObject {
            put("type", "object")
        }
        override val category = ToolCategory.DEVICE

        override suspend fun execute(context: ToolContext, arguments: kotlinx.serialization.json.JsonObject): ToolResult {
            onExecute()
            return ToolResult.ok("echo:" + arguments["text"].toString())
        }
    }
}

