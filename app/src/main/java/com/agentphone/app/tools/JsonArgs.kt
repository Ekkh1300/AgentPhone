package com.agentphone.app.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

fun JsonObject.string(name: String, default: String = ""): String =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: default

fun JsonObject.optString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.integer(name: String, default: Int): Int =
    (this[name] as? JsonPrimitive)?.intOrNull ?: default

fun JsonObject.bool(name: String, default: Boolean = false): Boolean =
    (this[name] as? JsonPrimitive)?.booleanOrNull ?: default
