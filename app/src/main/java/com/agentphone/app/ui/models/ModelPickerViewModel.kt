package com.agentphone.app.ui.models

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.R
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.repository.ChatRepository
import com.agentphone.app.data.repository.ProviderRepository
import com.agentphone.app.domain.model.ModelInfo
import com.agentphone.app.domain.model.ProviderProfile
import com.agentphone.app.domain.provider.ProviderAdapterRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelPickerState(
    val loading: Boolean = true,
    val provider: ProviderProfile? = null,
    val models: List<ModelInfo> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val selecting: String? = null,
)

@HiltViewModel
class ModelPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val providers: ProviderRepository,
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    private val adapters: ProviderAdapterRegistry,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val providerId: String = checkNotNull(savedStateHandle["providerId"])
    private val chatId: String? = savedStateHandle.get<String>("chatId")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(ModelPickerState())
    val state: StateFlow<ModelPickerState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val profile = providers.getProfile(providerId)
            if (profile == null) {
                _state.update {
                    it.copy(loading = false, error = context.getString(R.string.provider_not_found))
                }
                return@launch
            }
            _state.update { it.copy(provider = profile) }
            runCatching { adapters.adapterFor(profile).listModels(providers.resolve(profile)) }
                .onSuccess { models ->
                    _state.update { it.copy(loading = false, models = models, error = null) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: context.getString(R.string.could_not_load_models),
                        )
                    }
                }
        }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun select(model: String, onDone: () -> Unit) {
        if (model.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(selecting = model) }
            if (chatId != null) {
                val chat = chats.getChat(chatId)
                if (chat != null) {
                    chats.updateChat(chat.copy(providerId = providerId, model = model))
                }
            }
            settings.setActiveProvider(providerId)
            _state.update { it.copy(selecting = null) }
            onDone()
        }
    }
}
