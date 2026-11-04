package com.agentphone.app.data.remote

import com.agentphone.app.domain.provider.LlmStreamEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiStreamAccumulatorTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun accumulatesContentReasoningAndUsage() {
        val accumulator = OpenAiStreamAccumulator(json)
        accumulator.consume(
            """{"choices":[{"delta":{"reasoning_content":"Think "},"index":0}]}""",
        )
        accumulator.consume("""{"choices":[{"delta":{"content":"Hel"},"index":0}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}""")
        accumulator.consume(
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}""",
        )
        val completed = accumulator.completed() as LlmStreamEvent.Completed
        assertEquals("stop", completed.finishReason)
        assertEquals(10, completed.usage?.promptTokens)
        assertEquals(2, completed.usage?.completionTokens)
        assertEquals(12, completed.usage?.totalTokens)
        assertEquals("Hello", accumulator.contentSnapshot())
        assertEquals("Think ", accumulator.reasoningSnapshot())
    }

    @Test
    fun assemblesFragmentedToolCalls() {
        val accumulator = OpenAiStreamAccumulator(json)
        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"files_write","arguments":"{\"pa"}}]},"finish_reason":null}]}""",
        )
        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.md\",\"content\":\"hi\"}"}}]}}]}""",
        )
        val completed = accumulator.completed() as LlmStreamEvent.Completed
        assertEquals(1, completed.toolCalls.size)
        assertEquals("call_a", completed.toolCalls[0].id)
        assertEquals("files_write", completed.toolCalls[0].name)
        assertEquals("{\"path\":\"a.md\",\"content\":\"hi\"}", completed.toolCalls[0].arguments)
    }

    @Test
    fun assemblesParallelToolCallsByIndex() {
        val accumulator = OpenAiStreamAccumulator(json)
        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"one","arguments":"{"}},{"index":1,"id":"b","function":{"name":"two","arguments":"{"}}]}}]}""",
        )
        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"\"x\":1}"}},{"index":0,"function":{"arguments":"\"y\":2}"}}]}}]}""",
        )
        val calls = (accumulator.completed() as LlmStreamEvent.Completed).toolCalls
        assertEquals(listOf("a", "b"), calls.map { it.id })
        assertEquals("{\"y\":2}", calls[0].arguments)
        assertEquals("{\"x\":1}", calls[1].arguments)
    }

    @Test
    fun supportsLegacyFunctionCall() {
        val accumulator = OpenAiStreamAccumulator(json)
        accumulator.consume(
            """{"choices":[{"delta":{"function_call":{"name":"echo","arguments":"{\"x\":1}"}},"finish_reason":"function_call"}]}""",
        )
        val completed = accumulator.completed() as LlmStreamEvent.Completed
        assertEquals("echo", completed.toolCalls.single().name)
        assertEquals("{\"x\":1}", completed.toolCalls.single().arguments)
        assertTrue(completed.finishReason == "function_call")
    }

    @Test
    fun parsesContentArray() {
        val accumulator = OpenAiStreamAccumulator(json)
        accumulator.consume(
            """{"choices":[{"delta":{"content":[{"type":"text","text":"A"},{"type":"text","text":"B"}]}}]}""",
        )
        assertEquals("AB", accumulator.contentSnapshot())
    }
}
