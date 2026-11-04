package com.agentphone.app.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object Schemas {
    fun obj(
        required: List<String> = emptyList(),
        properties: JsonObject,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(it) } }
        }
        put("additionalProperties", false)
    }

    fun string(description: String, enumValues: List<String>? = null): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("description", description)
            if (!enumValues.isNullOrEmpty()) {
                putJsonArray("enum") { enumValues.forEach { add(it) } }
            }
        }

    fun number(description: String, minimum: Double? = null, maximum: Double? = null): JsonObject =
        buildJsonObject {
            put("type", "number")
            put("description", description)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

    fun integer(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject =
        buildJsonObject {
            put("type", "integer")
            put("description", description)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

    fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    fun objectOf(description: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", true)
    }
}
