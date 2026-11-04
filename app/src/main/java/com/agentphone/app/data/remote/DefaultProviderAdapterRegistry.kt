package com.agentphone.app.data.remote

import com.agentphone.app.domain.model.ProviderProfile
import com.agentphone.app.domain.provider.ProviderAdapter
import com.agentphone.app.domain.provider.ProviderAdapterRegistry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultProviderAdapterRegistry @Inject constructor(
    adapters: Set<@JvmSuppressWildcards ProviderAdapter>,
) : ProviderAdapterRegistry {

    private val byFormat = adapters.associateBy { it.apiFormat }

    override fun adapterFor(profile: ProviderProfile): ProviderAdapter =
        byFormat[profile.apiFormat] ?: byFormat.getValue(OPENAI_FORMAT)

    override val supportedFormats: List<String>
        get() = byFormat.keys.sorted()

    override val displayNames: Map<String, String> = mapOf(
        OPENAI_FORMAT to "OpenAI-compatible",
    )

    private companion object {
        const val OPENAI_FORMAT = "openai"
    }
}
