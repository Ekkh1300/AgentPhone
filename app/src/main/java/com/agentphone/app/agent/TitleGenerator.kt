package com.agentphone.app.agent

import com.agentphone.app.domain.model.ResolvedProvider
import com.agentphone.app.domain.provider.LlmMessage
import com.agentphone.app.domain.provider.LlmRequest
import com.agentphone.app.domain.provider.ProviderAdapterRegistry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TitleGenerator @Inject constructor(
    private val adapters: ProviderAdapterRegistry,
) {
    suspend fun generate(
        provider: ResolvedProvider,
        model: String,
        firstUserMessage: String,
        firstAssistantMessage: String,
    ): String? = runCatching {
        val completion = adapters.adapterFor(provider.profile).complete(
            LlmRequest(
                provider = provider,
                model = model,
                messages = listOf(
                    LlmMessage(
                        role = "system",
                        content = "Write a short chat title: 2-5 words, no quotes, no punctuation at the end, " +
                            "in the same language as the user. Output only the title.",
                    ),
                    LlmMessage(
                        role = "user",
                        content = "User: ${firstUserMessage.take(1_500)}\n\n" +
                            "Assistant: ${firstAssistantMessage.take(1_500)}",
                    ),
                ),
                maxTokens = 24,
                temperature = 0.2f,
                stream = false,
            ),
        )
        if (com.agentphone.app.BuildConfig.DEBUG) {
            android.util.Log.d(
                "AgentPhoneTitle",
                "completion content='${completion.content.take(120)}' finish=${completion.finishReason}",
            )
        }
        completion.content
            .trim()
            .trim('"', '\'', '.', '。', '؟')
            .replace(Regex("\\s+"), " ")
            .take(60)
            .takeIf { it.isNotBlank() }
    }.onFailure {
        if (com.agentphone.app.BuildConfig.DEBUG) {
            android.util.Log.w("AgentPhoneTitle", "title generation failed: ${it.message}")
        }
    }.getOrNull()
}
