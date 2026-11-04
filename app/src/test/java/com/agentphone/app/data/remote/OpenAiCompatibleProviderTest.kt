package com.agentphone.app.data.remote

import com.agentphone.app.domain.model.ProviderProfile
import com.agentphone.app.domain.model.ResolvedProvider
import com.agentphone.app.domain.provider.LlmMessage
import com.agentphone.app.domain.provider.LlmRequest
import com.agentphone.app.domain.provider.LlmStreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleProviderTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun streamsTextUsageAndSendsBearerToken() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"Hel"}}]}

                    data: {"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val events = adapter().stream(request()).toList()

        assertEquals(
            listOf("Hel", "lo"),
            events.filterIsInstance<LlmStreamEvent.Text>().map { it.value },
        )
        val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
        assertEquals("stop", completed.finishReason)
        assertEquals(5, completed.usage?.promptTokens)
        assertEquals(2, completed.usage?.completionTokens)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test-secret", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun reassemblesToolCallsSplitAcrossChunks() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"files_read","arguments":"{\"pa"}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"notes.txt\"}"}}]}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val completed = adapter().stream(request())
            .toList()
            .filterIsInstance<LlmStreamEvent.Completed>()
            .single()

        assertEquals("tool_calls", completed.finishReason)
        val call = completed.toolCalls.single()
        assertEquals("call_a", call.id)
        assertEquals("files_read", call.name)
        assertEquals("""{"path":"notes.txt"}""", call.arguments)
    }

    @Test
    fun mapsRateLimitWithRetryAfter() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "7")
                .setBody("""{"error":{"message":"slow down"}}"""),
        )

        val failure = runCatching {
            adapter().stream(request()).toList()
        }.exceptionOrNull()

        assertTrue("expected ProviderException, got $failure", failure is ProviderException)
        val error = failure as ProviderException
        assertEquals(ProviderErrorKind.RATE_LIMIT, error.kind)
        assertEquals(7, error.retryAfterSeconds)
        assertTrue(error.message.contains("429"))
        assertTrue(error.message.contains("slow down"))
    }

    @Test
    fun connectionTestFallsBackToChatWhenModelsIsMissing() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"id":"1","model":"test-model","choices":[{"index":0,
                    "message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """.trimIndent(),
                ),
        )

        val result = adapter().test(resolvedProvider())

        assertTrue(result.message, result.ok)
        assertEquals("/v1/models", server.takeRequest().path)
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    private fun adapter(): OpenAiCompatibleProvider = OpenAiCompatibleProvider(
        client = OkHttpClient(),
        json = json,
        imageEncoder = object : ImageEncoder {
            override fun encode(uri: String): String = uri
        },
    )

    private fun resolvedProvider(): ResolvedProvider = ResolvedProvider(
        profile = ProviderProfile(
            id = "test",
            name = "Test provider",
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            defaultModel = "test-model",
        ),
        apiKey = "sk-test-secret",
    )

    private fun request(): LlmRequest = LlmRequest(
        provider = resolvedProvider(),
        model = "test-model",
        messages = listOf(LlmMessage("user", "hello")),
        stream = true,
    )
}
