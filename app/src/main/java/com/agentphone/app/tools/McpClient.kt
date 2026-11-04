package com.agentphone.app.tools

import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.remote.SseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

@Singleton
class McpClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) {
    private val requestId = AtomicInteger(1)
    private var sessionId: String? = null
    private var initializedFor: String? = null

    private val http: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    suspend fun listTools(): List<McpToolInfo> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val response = rpc(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId.getAndIncrement())
                put("method", "tools/list")
                putJsonObject("params") { }
            },
            expectResult = true,
        )
        val tools = (response["result"] as? JsonObject)?.get("tools") as? JsonArray
            ?: return@withContext emptyList()
        tools.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            McpToolInfo(
                name = name,
                description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                inputSchema = (obj["inputSchema"] as? JsonObject) ?: buildJsonObject { },
            )
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        val response = rpc(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId.getAndIncrement())
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", name)
                    put("arguments", arguments)
                }
            },
            expectResult = true,
        )
        val result = response["result"] as? JsonObject
            ?: error("MCP server returned no result.")
        val isError = (result["isError"] as? JsonPrimitive)?.contentOrNull?.toBoolean() == true
        val content = (result["content"] as? JsonArray)?.joinToString("\n") { element ->
            val obj = element as? JsonObject ?: return@joinToString ""
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                "image" -> "[image ${(obj["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "image"}]"
                "resource" -> (obj["resource"] as? JsonObject)?.toString().orEmpty()
                else -> obj.toString()
            }
        }.orEmpty()
        if (isError) error(content.ifBlank { "MCP tool returned an error." })
        content.ifBlank { "MCP tool completed with no text output." }
    }

    private suspend fun ensureInitialized() {
        val current = settings.current()
        if (!current.mcpEnabled) error("MCP is disabled. Enable it in Settings > Agent.")
        val url = current.mcpServerUrl.trim()
        if (url.isBlank()) error("MCP server URL is not configured.")
        if (initializedFor == url) return

        val init = rpc(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId.getAndIncrement())
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", "2025-06-18")
                    putJsonObject("capabilities") { }
                    putJsonObject("clientInfo") {
                        put("name", "AgentPhone")
                        put("version", "1.0.0")
                    }
                }
            },
            expectResult = true,
            allowSession = false,
        )
        if (init["result"] == null && init["error"] != null) {
            error("MCP initialization failed: ${init["error"]}")
        }
        rpc(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            },
            expectResult = false,
        )
        initializedFor = url
    }

    private suspend fun rpc(
        payload: JsonObject,
        expectResult: Boolean,
        allowSession: Boolean = true,
    ): JsonObject {
        val current = settings.current()
        val key = runCatching { settings.mcpKey() }.getOrNull()
        val request = Request.Builder()
            .url(current.mcpServerUrl.trim())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply {
                if (key != null && key.isNotBlank()) header("Authorization", "Bearer $key")
                if (allowSession) sessionId?.let { header("Mcp-Session-Id", it) }
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            if (response.code == 202 && !expectResult) return buildJsonObject { }
            if (!response.isSuccessful) {
                error("MCP server returned HTTP ${response.code}: ${response.body?.string()?.take(300).orEmpty()}")
            }
            val body = response.body?.string().orEmpty()
            if (!expectResult && body.isBlank()) return buildJsonObject { }
            val jsonText = if (response.header("Content-Type")?.contains("text/event-stream") == true) {
                val parser = SseParser()
                var data: String? = null
                body.lineSequence().forEach { line ->
                    val event = parser.feed(line)
                    if (event != null && data == null && event.data.isNotBlank()) {
                        data = event.data
                    }
                }
                data ?: parser.endOfStream()?.data
                ?: error("MCP SSE response contained no data.")
            } else {
                body
            }
            val obj = runCatching { json.parseToJsonElement(jsonText).jsonObject }
                .getOrElse { error("MCP returned invalid JSON.") }
            (obj["error"] as? JsonObject)?.let { error ->
                error("MCP error: ${(error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()}")
            }
            return obj
        }
    }
}
