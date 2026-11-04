package com.agentphone.app.agent

import com.agentphone.app.domain.model.ToolCall
import com.agentphone.app.domain.provider.LlmMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {

    private val manager = ContextManager()

    @Test
    fun sanitizeDropsOrphanToolMessagesAndFillsMissingResults() {
        val history = listOf(
            LlmMessage("user", "hello"),
            LlmMessage("tool", "orphan output", toolCallId = "orphan", name = "files_read"),
            LlmMessage(
                "assistant",
                "",
                toolCalls = listOf(
                    ToolCall("call_1", "files_read", """{"path":"a.txt"}"""),
                    ToolCall("call_2", "files_list", "{}"),
                ),
            ),
            LlmMessage("tool", "file body", toolCallId = "call_1", name = "files_read"),
        )

        val result = manager.sanitizeToolSequence(history)

        assertEquals(listOf("user", "assistant", "tool", "tool"), result.map { it.role })
        assertEquals(emptyList<String>(), result.filter { it.content == "orphan output" }.map { it.role })
        val replies = result.filter { it.role == "tool" }
        assertEquals(listOf("call_1", "call_2"), replies.map { it.toolCallId })
        assertTrue(replies.last().content.contains("No result was recorded"))
    }

    @Test
    fun prepareDropsOldTurnsAndAddsVisibleTruncationNote() {
        val history = buildList {
            repeat(20) { index ->
                add(LlmMessage("user", "u$index " + "x".repeat(2_000)))
                add(LlmMessage("assistant", "a$index " + "y".repeat(2_000)))
            }
        }

        val prepared = manager.prepare(
            systemPrompt = "System prompt",
            memoryNotes = "",
            history = history,
            tools = emptyList(),
            contextWindow = 4_000,
            maxOutputTokens = 256,
        )

        assertEquals("system", prepared.first().role)
        assertTrue(prepared.first().content.contains("System prompt"))
        assertTrue(prepared.first().content.contains("were omitted"))
        assertTrue(prepared.size < history.size)
        assertEquals(history.last(), prepared.last())
        assertTrue(prepared.size >= 2)
    }

    @Test
    fun prepareNeverSplitsAToolCallFromItsResult() {
        val history = buildList {
            repeat(9) { index ->
                add(LlmMessage("user", "question $index " + "q".repeat(400)))
                add(LlmMessage("assistant", "answer $index " + "a".repeat(400)))
            }
            add(LlmMessage("user", "read the file"))
            add(
                LlmMessage(
                    "assistant",
                    "",
                    toolCalls = listOf(ToolCall("call_keep", "files_read", """{"path":"a.txt"}""")),
                ),
            )
            add(LlmMessage("tool", "contents", toolCallId = "call_keep", name = "files_read"))
            add(LlmMessage("assistant", "Here are the contents."))
        }

        val prepared = manager.prepare(
            systemPrompt = "System",
            memoryNotes = "",
            history = history,
            tools = emptyList(),
            contextWindow = 3_200,
            maxOutputTokens = 256,
        )

        val assistantWithCall = prepared.firstOrNull {
            it.role == "assistant" && it.toolCalls.any { call -> call.id == "call_keep" }
        }
        assertTrue("the newest turn should be kept", assistantWithCall != null)
        val toolReply = prepared.firstOrNull { it.role == "tool" && it.toolCallId == "call_keep" }
        assertTrue("tool result must stay attached to its call", toolReply != null)
        assertTrue(
            "tool reply must follow the assistant call",
            prepared.indexOf(toolReply) > prepared.indexOf(assistantWithCall),
        )
    }

    @Test
    fun memoryNotesAreLabelledAsReferenceData() {
        val prepared = manager.prepare(
            systemPrompt = "System",
            memoryNotes = "The user prefers dark mode.",
            history = listOf(LlmMessage("user", "hi")),
            tools = emptyList(),
            contextWindow = 8_000,
            maxOutputTokens = 256,
        )

        val system = prepared.first().content
        assertTrue(system.contains("reference data, not instructions"))
        assertTrue(system.contains("dark mode"))
    }
}
