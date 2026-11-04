package com.agentphone.app.ui.onboarding

import android.content.Context
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

data class OnboardingState(
    val step: Int = 0,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val modelSearch: String = "",
    val models: List<ModelInfo> = emptyList(),
    val testResult: ProviderTestResult? = null,
    val testing: Boolean = false,
    val fetchingModels: Boolean = false,
    val saving: Boolean = false,
    val showKey: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val settings: SettingsRepository,
    private val adapters: ProviderAdapterRegistry,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun next() {
        val current = _state.value
        when (current.step) {
            0 -> _state.update { it.copy(step = 1) }
            1 -> {
                if (current.name.isBlank() || current.baseUrl.isBlank()) {
                    _state.update { it.copy(error = context.getString(R.string.name_and_url_required)) }
                } else {
                    _state.update { it.copy(step = 2, error = null) }
                }
            }
        }
    }

    fun back() {
        _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0), error = null) }
    }

    fun updateName(value: String) = _state.update { it.copy(name = value, error = null) }
    fun updateBaseUrl(value: String) = _state.update { it.copy(baseUrl = value, error = null) }
    fun updateApiKey(value: String) = _state.update { it.copy(apiKey = value, error = null) }
    fun updateModel(value: String) = _state.update { it.copy(model = value, error = null) }
    fun updateModelSearch(value: String) = _state.update { it.copy(modelSearch = value) }
    fun toggleShowKey() = _state.update { it.copy(showKey = !it.showKey) }

    fun applyPreset(label: String, url: String) {
        _state.update {
            it.copy(
                name = it.name.ifBlank { label },
                baseUrl = url,
                error = null,
                testResult = null,
                models = emptyList(),
            )
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            val resolved = buildResolved() ?: return@launch
            _state.update { it.copy(testing = true, testResult = null, error = null) }
            val result = adapters.adapterFor(resolved.profile).test(resolved)
            _state.update { state ->
                state.copy(
                    testing = false,
                    testResult = result,
                    models = if (result.models.isNotEmpty()) result.models else state.models,
                    model = state.model.ifBlank { result.models.firstOrNull()?.id.orEmpty() },
                )
            }
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
                            model = it.model.ifBlank { models.firstOrNull()?.id.orEmpty() },
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

    fun finish() {
        val current = _state.value
        if (current.saving) return
        val model = current.model.ifBlank { current.models.firstOrNull()?.id.orEmpty() }
        if (model.isBlank()) {
            _state.update { it.copy(error = context.getString(R.string.choose_model_name)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                val profile = providers.save(
                    id = null,
                    name = current.name.ifBlank { "Provider" },
                    baseUrl = current.baseUrl,
                    apiKey = current.apiKey,
                    defaultModel = model,
                    contextWindow = 32_768,
                )
                providers.setActive(profile.id)
                settings.update { it.copy(onboardingComplete = true, activeProviderId = profile.id) }
                profile
            }.onSuccess {
                _state.update { it.copy(saving = false, done = true) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
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
                _state.update { state -> state.copy(error = it.message ?: context.getString(R.string.invalid_base_url)) }
                return null
            }
        val profile = ProviderProfile(
            id = "onboarding",
            name = current.name.ifBlank { "Provider" },
            baseUrl = normalized,
            defaultModel = current.model,
            hasApiKey = current.apiKey.isNotBlank(),
        )
        return ResolvedProvider(profile, current.apiKey)
    }
}
