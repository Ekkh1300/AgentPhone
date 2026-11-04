package com.agentphone.app.data.remote

import com.agentphone.app.domain.model.ModelInfo
import com.agentphone.app.domain.model.ResolvedProvider
import com.agentphone.app.domain.model.TokenUsage
import com.agentphone.app.domain.model.ToolCall
import com.agentphone.app.domain.provider.LlmCompletion
import com.agentphone.app.domain.provider.LlmMessage
import com.agentphone.app.domain.provider.LlmRequest
import com.agentphone.app.domain.provider.LlmStreamEvent
import com.agentphone.app.domain.provider.ProviderAdapter
import com.agentphone.app.domain.provider.ProviderTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAiCompatibleProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    imageEncoder: ImageEncoder,
) : ProviderAdapter {

    override val apiFormat: String = "openai"

    private val bodyBuilder = OpenAiRequestBuilder(json, imageEncoder)
    private val clientWithTimeouts: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = channelFlow {
        val callRef = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)
        val parser = SseParser()
        val accumulator = OpenAiStreamAccumulator(json)

        launch(Dispatchers.IO) {
            try {
                val httpRequest = runCatching { buildHttpRequest(request, stream = true) }
                    .getOrElse { throw wrapBuildError(it) }
                val call = client.newCall(httpRequest)
                callRef.set(call)
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ProviderErrors.http(
                            response.code,
                            response.body?.string().orEmpty(),
                            response.header("Retry-After"),
                            request.provider.profile.baseUrl,
                        )
                    }
                    val source = response.body?.source()
                        ?: throw ProviderErrors.emptyResponse()
                    var sawDone = false
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val event = parser.feed(line) ?: continue
                        val data = event.data
                        if (data.trim() == "[DONE]") {
                            sawDone = true
                            break
                        }
                        if (data.isBlank()) continue
                        accumulator.consume(data).forEach { trySendBlocking(it) }
                    }
                    if (!sawDone) {
                        parser.endOfStream()?.data
                            ?.takeIf { it.isNotBlank() && it.trim() != "[DONE]" }
                            ?.let { accumulator.consume(it).forEach { e -> trySendBlocking(e) } }
                    }
                    val completion = accumulator.completed()
                    if (!sawDone && completion.finishReason == null) {
                        throw ProviderErrors.incompleteStream()
                    }
                    trySendBlocking(completion)
                    close()
                }
            } catch (ce: CancellationException) {
                callRef.get()?.cancel()
                throw ce
            } catch (pe: ProviderException) {
                close(pe)
            } catch (t: Throwable) {
                close(ProviderErrors.network(t))
            }
        }

        awaitClose { callRef.get()?.cancel() }
    }.buffer(256)

    override suspend fun complete(request: LlmRequest): LlmCompletion = withContext(Dispatchers.IO) {
        val httpRequest = runCatching { buildHttpRequest(request, stream = false) }
            .getOrElse { throw wrapBuildError(it) }
        try {
            client.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ProviderErrors.http(
                        response.code,
                        body,
                        response.header("Retry-After"),
                        request.provider.profile.baseUrl,
                    )
                }
                parseCompletion(body)
            }
        } catch (pe: ProviderException) {
            throw pe
        } catch (t: Throwable) {
            throw ProviderErrors.network(t)
        }
    }

    override suspend fun listModels(provider: ResolvedProvider): List<ModelInfo> =
        withContext(Dispatchers.IO) {
            val url = runCatching { UrlNormalizer.models(provider.profile.baseUrl) }
                .getOrElse { throw wrapBuildError(it) }
            val request = Request.Builder()
                .url(url)
                .get()
                .applyHeaders(provider, accept = "application/json")
                .build()
            try {
                clientWithTimeouts.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw ProviderErrors.http(
                            response.code,
                            body,
                            response.header("Retry-After"),
                            provider.profile.baseUrl,
                        )
                    }
                    parseModels(body)
                }
            } catch (pe: ProviderException) {
                throw pe
            } catch (t: Throwable) {
                throw ProviderErrors.network(t)
            }
        }

    override suspend fun test(provider: ResolvedProvider): ProviderTestResult {
        val started = System.currentTimeMillis()
        return try {
            val models = listModels(provider)
            ProviderTestResult(
                ok = true,
                message = if (models.isEmpty()) {
                    "Connected. The server returned no models; enter a model name manually."
                } else {
                    "Connected. ${models.size} model${if (models.size == 1) "" else "s"} available."
                },
                latencyMs = System.currentTimeMillis() - started,
                models = models,
            )
        } catch (pe: ProviderException) {
            if (pe.kind == ProviderErrorKind.NOT_FOUND && provider.profile.defaultModel.isNotBlank()) {
                return try {
                    complete(
                        LlmRequest(
                            provider = provider,
                            model = provider.profile.defaultModel,
                            messages = listOf(
                                LlmMessage(
                                    role = "user",
                                    content = "Reply with the single word: ok",
                                ),
                            ),
                            maxTokens = 4,
                            temperature = 0f,
                            stream = false,
                        ),
                    )
                    ProviderTestResult(
                        ok = true,
                        message = "Connected. The server does not expose /models, but a chat " +
                            "completion succeeded with ${provider.profile.defaultModel}.",
                        latencyMs = System.currentTimeMillis() - started,
                        models = emptyList(),
                    )
                } catch (fallback: ProviderException) {
                    ProviderTestResult(
                        ok = false,
                        message = fallback.message,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                }
            }
            ProviderTestResult(
                ok = false,
                message = pe.message,
                latencyMs = System.currentTimeMillis() - started,
            )
        }
    }

    private fun buildHttpRequest(request: LlmRequest, stream: Boolean): Request {
        val bodyJson = bodyBuilder.body(request, stream)
        val payload = bodyJson.toString()
        val url = UrlNormalizer.chatCompletions(request.provider.profile.baseUrl)
        return Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA))
            .applyHeaders(
                request.provider,
                accept = if (stream) "text/event-stream" else "application/json",
            )
            .build()
    }

    private fun Request.Builder.applyHeaders(
        provider: ResolvedProvider,
        accept: String,
    ): Request.Builder {
        header("Accept", accept)
        header("Content-Type", "application/json")
        header("User-Agent", "AgentPhone/1.0 (Android)")
        if (provider.apiKey.isNotBlank() && provider.profile.customHeaders.keys.none {
                it.equals("Authorization", ignoreCase = true)
            }
        ) {
            header("Authorization", "Bearer ${provider.apiKey}")
        }
        if (provider.profile.organization.isNotBlank()) {
            header("OpenAI-Organization", provider.profile.organization)
        }
        if (provider.profile.project.isNotBlank()) {
            header("OpenAI-Project", provider.profile.project)
        }
        provider.profile.customHeaders.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank() &&
                !name.contains('\n') && !value.contains('\n')
            ) {
                header(name, value)
            }
        }
        return this
    }

    private fun parseCompletion(body: String): LlmCompletion {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ProviderErrors.parse(it.message ?: "invalid JSON", it) }
        (root["error"] as? JsonObject)?.let { error ->
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull
                ?: "Provider returned an error."
            throw ProviderException(ProviderErrorKind.BAD_REQUEST, message)
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw ProviderErrors.emptyResponse()
        val message = choice["message"] as? JsonObject
        val content = extractContent(message?.get("content"))
        val reasoning = extractReasoning(message)
        val toolCalls = parseToolCalls(message)
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        val usage = parseUsage(root["usage"] as? JsonObject)
        return LlmCompletion(content, reasoning, toolCalls, finish, usage)
    }

    private fun parseModels(body: String): List<ModelInfo> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ProviderErrors.parse(it.message ?: "invalid JSON", it) }
        (root["error"] as? JsonObject)?.let { error ->
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull
                ?: "Provider returned an error."
            throw ProviderException(ProviderErrorKind.BAD_REQUEST, message)
        }
        val array = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
            ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                    ModelInfo(it)
                }
                is JsonObject -> {
                    val id = (element["id"] as? JsonPrimitive)?.contentOrNull
                        ?: (element["name"] as? JsonPrimitive)?.contentOrNull
                    id?.takeIf { it.isNotBlank() }?.let {
                        ModelInfo(
                            id = it,
                            ownedBy = (element["owned_by"] as? JsonPrimitive)?.contentOrNull,
                            created = (element["created"] as? JsonPrimitive)?.contentOrNull
                                ?.toLongOrNull(),
                        )
                    }
                }
                else -> null
            }
        }.distinctBy { it.id }.sortedBy { it.id.lowercase() }
    }

    private fun extractContent(element: Any?): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.joinToString("") { part ->
            val obj = part as? JsonObject
            (obj?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        else -> ""
    }

    private fun extractReasoning(message: JsonObject?): String? {
        if (message == null) return null
        for (key in listOf("reasoning_content", "reasoning", "thinking", "analysis")) {
            val value = message[key]
            if (value is JsonPrimitive && !value.contentOrNull.isNullOrEmpty()) {
                return value.contentOrNull
            }
            if (value is JsonObject) {
                val text = (value["text"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }

    private fun parseToolCalls(message: JsonObject?): List<ToolCall> {
        if (message == null) return emptyList()
        val result = mutableListOf<ToolCall>()
        (message["tool_calls"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val fn = obj["function"] as? JsonObject ?: return@forEach
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
                ?: "call_${name}_${result.size}"
            val args = when (val raw = fn["arguments"]) {
                is JsonPrimitive -> raw.contentOrNull.orEmpty()
                null -> "{}"
                else -> raw.toString()
            }
            result += ToolCall(id, name, args.ifBlank { "{}" })
        }
        (message["function_call"] as? JsonObject)?.let { fn ->
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull
            val args = (fn["arguments"] as? JsonPrimitive)?.contentOrNull
            if (!name.isNullOrBlank()) {
                result += ToolCall("call_${name}_0", name, args?.ifBlank { "{}" } ?: "{}")
            }
        }
        return result
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        fun number(vararg keys: String): Int {
            for (key in keys) {
                (usage[key] as? JsonPrimitive)?.intOrNull?.let { return it }
            }
            return 0
        }
        val prompt = number("prompt_tokens", "input_tokens")
        val completion = number("completion_tokens", "output_tokens")
        val total = number("total_tokens").takeIf { it > 0 } ?: (prompt + completion)
        if (prompt == 0 && completion == 0 && total == 0) return null
        return TokenUsage(prompt, completion, total)
    }

    private fun wrapBuildError(error: Throwable): ProviderException = when (error) {
        is ProviderException -> error
        is IllegalArgumentException -> ProviderException(
            ProviderErrorKind.BAD_REQUEST,
            error.message ?: "Invalid provider URL.",
            cause = error,
        )
        else -> ProviderErrors.network(error)
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
