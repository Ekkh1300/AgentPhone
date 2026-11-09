package com.agentphone.app.telegram

import com.agentphone.app.data.local.entity.AwayDecisionValue
import com.agentphone.app.data.local.entity.AwayUrgency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

data class AwayDecision(
    val decision: String,
    val replyText: String?,
    val confidence: Double,
    val reason: String,
    val urgency: String,
    val risks: List<String>,
) {
    val isReply: Boolean get() = decision == AwayDecisionValue.REPLY
    val isDraft: Boolean get() = decision == AwayDecisionValue.DRAFT
    val isIgnore: Boolean get() = decision == AwayDecisionValue.IGNORE
    val isEscalate: Boolean get() = decision == AwayDecisionValue.ESCALATE

    /** Exactly the shape the model was asked to produce, used by the simulator. */
    fun toJsonText(): String = buildJsonObject {
        put("decision", decision)
        put("reply_text", replyText)
        put("confidence", confidence)
        put("reason", reason)
        put("urgency", urgency)
        put(
            "detected_risks",
            buildJsonArray { risks.forEach { add(JsonPrimitive(it)) } },
        )
    }.toString()
}

sealed interface DecisionParse {
    data class Valid(val decision: AwayDecision) : DecisionParse
    data class Invalid(val reason: String) : DecisionParse
}

/**
 * Parses the responder's strict JSON. The model is told to output exactly this object; if it adds
 * prose around it, the first balanced JSON object is still extracted. Nothing is repaired silently:
 * an invalid object is reported so the caller can retry once and then fall back to a draft.
 */
object AwayDecisionParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(raw: String): DecisionParse {
        val candidate = extractObject(raw)
            ?: return DecisionParse.Invalid("No JSON object found in the model output.")
        val element = runCatching { json.parseToJsonElement(candidate) }
            .getOrElse { error ->
                return DecisionParse.Invalid("Invalid JSON: ${error.message?.take(160)}")
            }
        val obj = element as? JsonObject
            ?: return DecisionParse.Invalid("The decision must be a JSON object.")

        val decision = obj.string("decision")?.lowercase()
            ?: return DecisionParse.Invalid("Missing decision.")
        if (decision !in VALID_DECISIONS) {
            return DecisionParse.Invalid("Unknown decision '$decision'.")
        }
        val confidenceElement = obj["confidence"] as? JsonPrimitive
        if (confidenceElement == null || confidenceElement.isString) {
            return DecisionParse.Invalid("Missing numeric confidence.")
        }
        val confidence = confidenceElement.doubleOrNull
            ?: return DecisionParse.Invalid("Confidence is not a number.")
        if (confidence < 0.0 || confidence > 1.0) {
            return DecisionParse.Invalid("Confidence must be between 0 and 1.")
        }
        val urgency = obj.string("urgency")?.lowercase()
            ?: return DecisionParse.Invalid("Missing urgency.")
        if (urgency !in VALID_URGENCIES) {
            return DecisionParse.Invalid("Unknown urgency '$urgency'.")
        }
        val reason = obj.string("reason")
            ?: return DecisionParse.Invalid("Missing reason.")

        val replyElement = obj["reply_text"]
        val replyText = when {
            replyElement == null || replyElement is JsonNull -> null
            replyElement is JsonPrimitive && replyElement.isString -> replyElement.content
            else -> return DecisionParse.Invalid("reply_text must be a string or null.")
        }
        if (decision == AwayDecisionValue.REPLY && replyText.isNullOrBlank()) {
            return DecisionParse.Invalid("A reply decision needs reply_text.")
        }
        if (replyText != null && replyText.length > MAX_REPLY_LENGTH) {
            return DecisionParse.Invalid("reply_text is too long.")
        }
        val risksElement = obj["detected_risks"]
            ?: return DecisionParse.Invalid("Missing detected_risks.")
        val risks = risksElement.riskList()
            ?: return DecisionParse.Invalid("detected_risks must be an array of strings.")

        return DecisionParse.Valid(
            AwayDecision(
                decision = decision,
                replyText = replyText?.trim(),
                confidence = confidence,
                reason = reason.take(500),
                urgency = urgency,
                risks = risks.take(8),
            ),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonElement.riskList(): List<String>? {
        val array = runCatching { jsonArray }.getOrNull() ?: return null
        val result = mutableListOf<String>()
        for (item in array) {
            val primitive = item as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            result += primitive.content.take(120)
        }
        return result
    }

    /** Returns the first balanced JSON object in the text, respecting quoted strings. */
    internal fun extractObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private val VALID_DECISIONS = setOf(
        AwayDecisionValue.REPLY,
        AwayDecisionValue.DRAFT,
        AwayDecisionValue.IGNORE,
        AwayDecisionValue.ESCALATE,
    )
    private val VALID_URGENCIES = setOf(AwayUrgency.LOW, AwayUrgency.NORMAL, AwayUrgency.HIGH)
    private const val MAX_REPLY_LENGTH = 1_500
}
