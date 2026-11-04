package com.agentphone.app.ui.providers

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.R
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.remote.UrlNormalizer
import com.agentphone.app.data.repository.ProviderRepository
import com.agentphone.app.domain.model.ModelInfo
import com.agentphone.app.domain.model.ProviderProfile
import com.agentphone.app.domain.model.ResolvedProvider
import com.agentphone.app.domain.provider.ProviderAdapterRegistry
import com.agentphone.app.domain.provider.ProviderTestResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProviderEditorState(
    val isNew: Boolean = true,
    val providerId: String? = null,
    val configured: Boolean = false,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val hasStoredKey: Boolean = false,
    val keyCleared: Boolean = false,
    val defaultModel: String = "",
    val customHeaders: String = "",
    val organization: String = "",
    val project: String = "",
    val contextWindow: String = "32768",
    val inputPrice: String = "0",
    val outputPrice: String = "0",
    val apiFormat: String = "openai",
    val testing: Boolean = false,
    val fetchingModels: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val testResult: ProviderTestResult? = null,
    val models: List<ModelInfo> = emptyList(),
    val error: String? = null,
    val showKey: Boolean = false,
)

@HiltViewModel
class ProviderEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val providers: ProviderRepository,
    private val settings: SettingsRepository,
    private val adapters: ProviderAdapterRegistry,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val providerId: String? = savedStateHandle.get<String>("providerId")
        ?.takeUnless { it == "new" || it.isBlank() }

    private val _state = MutableStateFlow(
        ProviderEditorState(isNew = providerId == null, providerId = providerId),
    )
    val state: StateFlow<ProviderEditorState> = _state.asStateFlow()

    init {
        if (providerId != null) {
            viewModelScope.launch {
                providers.getProfile(providerId)?.let { profile ->
                    _state.update {
                        it.copy(
                            configured = true,
                            name = profile.name,
                            baseUrl = profile.baseUrl,
                            defaultModel = profile.defaultModel,
                            customHeaders = profile.customHeaders.entries.joinToString("\n") {
                                "${it.key}: ${it.value}"
                            },
                            organization = profile.organization,
                            project = profile.project,
                            contextWindow = profile.contextWindow.toString(),
                            inputPrice = formatPrice(profile.inputPricePerMillion),
                            outputPrice = formatPrice(profile.outputPricePerMillion),
                            hasStoredKey = profile.hasApiKey,
                            apiFormat = profile.apiFormat,
                        )
                    }
                }
            }
        }
    }

    fun updateName(value: String) = _state.update { it.copy(name = value, error = null) }
    fun updateBaseUrl(value: String) = _state.update { it.copy(baseUrl = value, error = null) }
    fun updateApiKey(value: String) = _state.update { it.copy(apiKey = value, error = null) }
    fun updateModel(value: String) = _state.update { it.copy(defaultModel = value, error = null) }
    fun updateHeaders(value: String) = _state.update { it.copy(customHeaders = value) }
    fun updateOrganization(value: String) = _state.update { it.copy(organization = value) }
    fun updateProject(value: String) = _state.update { it.copy(project = value) }
    fun updateContextWindow(value: String) = _state.update { it.copy(contextWindow = value.filter(Char::isDigit)) }
    fun updateInputPrice(value: String) = _state.update { it.copy(inputPrice = value) }
    fun updateOutputPrice(value: String) = _state.update { it.copy(outputPrice = value) }
    fun toggleShowKey() = _state.update { it.copy(showKey = !it.showKey) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun removeStoredKey() = _state.update {
        it.copy(apiKey = "", hasStoredKey = false, keyCleared = true, showKey = false, error = null)
    }

    fun testConnection() {
        viewModelScope.launch {
            val resolved = buildResolved() ?: return@launch
            _state.update { it.copy(testing = true, testResult = null, error = null) }
            val result = adapters.adapterFor(resolved.profile).test(resolved)
            _state.update { it.copy(testing = false, testResult = result) }
        }
    }

    fun fetchModels() {
        viewModelScope.launch {
            val resolved = buildResolved() ?: return@launch
            _state.update { it.copy(fetchingModels = true, error = null) }
            runCatching { adapters.adapterFor(resolved.profile).listModels(resolved) }
                .onSuccess { models ->
                    _state.update {
                        it.copy(
                            fetchingModels = false,
                            models = models,
                            defaultModel = it.defaultModel.ifBlank {
                                models.firstOrNull()?.id.orEmpty()
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            fetchingModels = false,
                            error = error.message ?: context.getString(R.string.could_not_fetch_models),
                        )
                    }
                }
        }
    }

    fun save() {
        val current = _state.value
        if (current.name.isBlank() || current.baseUrl.isBlank()) {
            _state.update { it.copy(error = context.getString(R.string.name_and_url_required)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                providers.save(
                    id = current.providerId,
                    name = current.name,
                    baseUrl = current.baseUrl,
                    apiKey = when {
                        current.keyCleared -> ""
                        current.apiKey.isNotBlank() -> current.apiKey
                        else -> null
                    },
                    defaultModel = current.defaultModel.trim(),
                    customHeaders = parseHeaders(current.customHeaders),
                    organization = current.organization,
                    project = current.project,
                    contextWindow = current.contextWindow.toIntOrNull() ?: 32_768,
                    inputPricePerMillion = current.inputPrice.toDoubleOrNull() ?: 0.0,
                    outputPricePerMillion = current.outputPrice.toDoubleOrNull() ?: 0.0,
                )
            }.onSuccess { profile ->
                if (settings.current().activeProviderId == null) {
                    providers.setActive(profile.id)
                }
                _state.update {
                    it.copy(
                        saving = false,
                        saved = true,
                        providerId = profile.id,
                        isNew = false,
                        baseUrl = profile.baseUrl,
                        apiKey = "",
                        hasStoredKey = profile.hasApiKey,
                        keyCleared = false,
                    )
                }
            }.onFailure { error ->
                _state.update { state ->
                    state.copy(
                        saving = false,
                        error = error.message ?: context.getString(R.string.could_not_save_provider),
                    )
                }
            }
        }
    }

    private suspend fun buildResolved(): ResolvedProvider? {
        val current = _state.value
        if (current.baseUrl.isBlank()) {
            _state.update { it.copy(error = context.getString(R.string.enter_base_url)) }
            return null
        }
        val normalized = runCatching { UrlNormalizer.normalizeBaseUrl(current.baseUrl) }
            .getOrElse {
                _state.update { state ->
                    state.copy(error = it.message ?: context.getString(R.string.invalid_base_url))
                }
                return null
            }
        val key = when {
            current.keyCleared -> ""
            current.apiKey.isNotBlank() -> current.apiKey
            current.hasStoredKey && current.providerId != null -> {
                runCatching { providers.resolve(current.providerId).apiKey }.getOrElse { "" }
            }
            else -> ""
        }
        val profile = ProviderProfile(
            id = current.providerId ?: "draft",
            name = current.name.ifBlank { "Provider" },
            baseUrl = normalized,
            defaultModel = current.defaultModel,
            apiFormat = current.apiFormat,
            customHeaders = parseHeaders(current.customHeaders),
            organization = current.organization,
            project = current.project,
            contextWindow = current.contextWindow.toIntOrNull() ?: 32_768,
            inputPricePerMillion = current.inputPrice.toDoubleOrNull() ?: 0.0,
            outputPricePerMillion = current.outputPrice.toDoubleOrNull() ?: 0.0,
            hasApiKey = key.isNotBlank(),
        )
        return ResolvedProvider(profile, key)
    }

    private fun parseHeaders(raw: String): Map<String, String> = raw.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.isBlank() || value.isBlank()) null else name to value
        }
        .toMap()

    private fun formatPrice(value: Double): String =
        if (value == 0.0) "0" else value.toString()
}
