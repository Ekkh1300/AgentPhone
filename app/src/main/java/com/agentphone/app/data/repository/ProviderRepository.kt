package com.agentphone.app.data.repository

import com.agentphone.app.data.local.dao.ProviderDao
import com.agentphone.app.data.local.entity.ProviderEntity
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.remote.ProviderException
import com.agentphone.app.data.remote.UrlNormalizer
import com.agentphone.app.data.security.SecretCipher
import com.agentphone.app.domain.model.ProviderProfile
import com.agentphone.app.domain.model.ResolvedProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRepository @Inject constructor(
    private val dao: ProviderDao,
    private val settings: SettingsRepository,
    private val cipher: SecretCipher,
    private val json: Json,
) {

    fun observeProfiles(): Flow<List<ProviderProfile>> =
        dao.observeAll().map { list -> list.map(::toProfile) }

    fun observeProfile(id: String): Flow<ProviderProfile?> =
        dao.observe(id).map { it?.let(::toProfile) }

    suspend fun getProfile(id: String): ProviderProfile? =
        dao.get(id)?.let(::toProfile)

    suspend fun firstProfile(): ProviderProfile? =
        observeProfiles().first().firstOrNull()

    suspend fun activeProfile(): ProviderProfile? {
        val activeId = settings.current().activeProviderId
        return activeId?.let { getProfile(it) } ?: firstProfile()
    }

    suspend fun resolve(id: String): ResolvedProvider {
        val entity = dao.get(id) ?: throw ProviderException(
            com.agentphone.app.data.remote.ProviderErrorKind.NOT_FOUND,
            "This provider no longer exists.",
        )
        val key = if (entity.apiKeyEncrypted.isBlank()) {
            ""
        } else {
            runCatching { cipher.decrypt(entity.apiKeyEncrypted) }
                .getOrElse { throw com.agentphone.app.data.remote.ProviderErrors.secretUnavailable() }
        }
        return ResolvedProvider(toProfile(entity), key)
    }

    suspend fun resolve(profile: ProviderProfile): ResolvedProvider = resolve(profile.id)

    /**
     * Creates or updates a provider. [apiKey] = null keeps the stored key; blank clears it
     * (useful for local servers that don't require auth).
     */
    suspend fun save(
        id: String?,
        name: String,
        baseUrl: String,
        apiKey: String?,
        defaultModel: String,
        customHeaders: Map<String, String> = emptyMap(),
        organization: String = "",
        project: String = "",
        contextWindow: Int = 32_768,
        inputPricePerMillion: Double = 0.0,
        outputPricePerMillion: Double = 0.0,
    ): ProviderProfile {
        require(name.isNotBlank()) { "Provider name is required." }
        val normalized = UrlNormalizer.normalizeBaseUrl(baseUrl)
        val existing = id?.let { dao.get(it) }
        val encrypted = when {
            apiKey != null -> if (apiKey.isBlank()) "" else cipher.encrypt(apiKey)
            existing != null -> existing.apiKeyEncrypted
            else -> ""
        }
        val headersJson = kotlinx.serialization.json.buildJsonObject {
            customHeaders.filterKeys { it.isNotBlank() }.forEach { (key, value) ->
                put(key, value)
            }
        }.toString()
        val entity = ProviderEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            baseUrl = normalized,
            apiKeyEncrypted = encrypted,
            defaultModel = defaultModel.trim(),
            apiFormat = existing?.apiFormat ?: "openai",
            customHeadersJson = headersJson,
            organization = organization.trim(),
            project = project.trim(),
            contextWindow = contextWindow.coerceIn(4_096, 2_000_000),
            inputPricePerMillion = inputPricePerMillion.coerceAtLeast(0.0),
            outputPricePerMillion = outputPricePerMillion.coerceAtLeast(0.0),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        dao.upsert(entity)
        if (settings.current().activeProviderId == null) {
            settings.setActiveProvider(entity.id)
        }
        return toProfile(entity)
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        if (settings.current().activeProviderId == id) {
            settings.setActiveProvider(dao.getAll().firstOrNull()?.id)
        }
    }

    suspend fun setActive(id: String) {
        settings.setActiveProvider(id)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        settings.setActiveProvider(null)
    }

    private fun toProfile(entity: ProviderEntity): ProviderProfile {
        val headers = runCatching {
            json.decodeFromString<Map<String, String>>(entity.customHeadersJson)
        }.getOrDefault(emptyMap())
            .filterValues { it.isNotBlank() }
        return ProviderProfile(
            id = entity.id,
            name = entity.name,
            baseUrl = entity.baseUrl,
            defaultModel = entity.defaultModel,
            apiFormat = entity.apiFormat,
            customHeaders = headers,
            organization = entity.organization,
            project = entity.project,
            contextWindow = entity.contextWindow,
            inputPricePerMillion = entity.inputPricePerMillion,
            outputPricePerMillion = entity.outputPricePerMillion,
            hasApiKey = entity.apiKeyEncrypted.isNotBlank(),
            createdAt = entity.createdAt,
        )
    }
}
