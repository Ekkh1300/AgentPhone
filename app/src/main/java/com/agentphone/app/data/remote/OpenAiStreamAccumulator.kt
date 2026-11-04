package com.agentphone.app.data.remote

import com.agentphone.app.domain.model.TokenUsage
import com.agentphone.app.domain.model.ToolCall
import com.agentphone.app.domain.provider.LlmStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.TreeMap

/** Accumulates OpenAI streaming chunks, including fragmented parallel tool calls. */
class OpenAiStreamAccumulator(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val calls = TreeMap<Int, PartialToolCall>()
    private val indexById = mutableMapOf<String, Int>()
    private var nextIndex = 0
    private var usage: TokenUsage? = null
    private var finishReason: String? = null
    private var completed = false

    fun consume(payload: String): List<LlmStreamEvent> {
        val events = mutableListOf<LlmStreamEvent>()
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw ProviderErrors.parse(it.message ?: "invalid JSON", it) }

        (root["error"] as? JsonObject)?.let { err ->
            val message = (err["message"] as? JsonPrimitive)?.contentOrNull
                ?: "The provider returned an error."
            throw ProviderException(ProviderErrorKind.BAD_REQUEST, message)
        }

        parseUsage(root)?.let { usage = it }

        val choices = (root["choices"] as? JsonArray).orEmpty()
        for (choiceElement in choices) {
            val choice = choiceElement as? JsonObject ?: continue
            val delta = (choice["delta"] as? JsonObject)
                ?: (choice["message"] as? JsonObject)

            if (delta != null) {
                extractText(delta["content"])?.takeIf { it.isNotEmpty() }?.let {
                    content.append(it)
                    events += LlmStreamEvent.Text(it)
                }
                extractReasoning(delta)?.takeIf { it.isNotEmpty() }?.let {
                    reasoning.append(it)
                    events += LlmStreamEvent.Reasoning(it)
                }
                readToolCalls(delta)
                readLegacyFunctionCall(delta)
            }

            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { finishReason = it }
        }
        return events
    }

    fun completed(): LlmStreamEvent.Completed {
        completed = true
        return LlmStreamEvent.Completed(
            finishReason = finishReason,
            toolCalls = assembledToolCalls(),
            usage = usage,
        )
    }

    val isCompleted: Boolean get() = completed

    fun contentSnapshot(): String = content.toString()

    fun reasoningSnapshot(): String = reasoning.toString()

    private fun readToolCalls(delta: JsonObject) {
        val array = delta["tool_calls"] as? JsonArray ?: return
        array.forEachIndexed { fallbackIndex, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            val explicitIndex = (obj["index"] as? JsonPrimitive)?.intOrNull
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val index = when {
                explicitIndex != null -> explicitIndex
                id != null && indexById.containsKey(id) -> indexById.getValue(id)
                id != null -> {
                    val occupied = calls[fallbackIndex]
                    val candidate = if (occupied == null || occupied.id == null || occupied.id == id) {
                        fallbackIndex
                    } else {
                        nextIndex
                    }
                    indexById[id] = candidate
                    candidate
                }
                else -> fallbackIndex
            }
            if (id != null) indexById[id] = index
            if (index >= nextIndex) nextIndex = index + 1
            val partial = calls.getOrPut(index) { PartialToolCall() }
            if (id != null) partial.id = id
            val fn = obj["function"] as? JsonObject ?: return@forEachIndexed
            (fn["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                partial.name = it
            }
            extractArguments(fn["arguments"])?.let { partial.arguments.append(it) }
        }
    }

    private fun readLegacyFunctionCall(delta: JsonObject) {
        val fn = delta["function_call"] as? JsonObject ?: return
        val partial = calls.getOrPut(-1) { PartialToolCall() }
        (fn["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            partial.name = it
        }
        extractArguments(fn["arguments"])?.let { partial.arguments.append(it) }
    }

    private fun assembledToolCalls(): List<ToolCall> =
        calls.values.mapNotNull { partial ->
            val name = partial.name ?: return@mapNotNull null
            ToolCall(
                id = partial.id?.takeIf { it.isNotBlank() }
                    ?: "call_${name}_${partial.hashCode().toUInt()}",
                name = name,
                arguments = partial.arguments.toString().ifBlank { "{}" },
            )
        }

    private fun extractArguments(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element.toString()
        else -> element.toString()
    }

    private fun extractText(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.joinToString("") { part ->
            val obj = part as? JsonObject
            (obj?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        else -> null
    }

    private fun extractReasoning(delta: JsonObject): String? {
        val keys = listOf("reasoning_content", "reasoning", "thinking", "analysis")
        for (key in keys) {
            val value = delta[key]
            if (value is JsonPrimitive && value.contentOrNull?.isNotEmpty() == true) {
                return value.contentOrNull
            }
            if (value is JsonObject) {
                val text = (value["text"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }

    private fun parseUsage(root: JsonObject): TokenUsage? {
        val usageObj = root["usage"] as? JsonObject ?: return null
        fun number(vararg keys: String): Int {
            for (key in keys) {
                val value = (usageObj[key] as? JsonPrimitive)?.intOrNull
                if (value != null) return value
            }
            return 0
        }
        val prompt = number("prompt_tokens", "input_tokens")
        val completion = number("completion_tokens", "output_tokens")
        val total = number("total_tokens").takeIf { it > 0 } ?: (prompt + completion)
        if (prompt == 0 && completion == 0 && total == 0) return null
        return TokenUsage(prompt, completion, total)
    }

    private data class PartialToolCall(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )
}
