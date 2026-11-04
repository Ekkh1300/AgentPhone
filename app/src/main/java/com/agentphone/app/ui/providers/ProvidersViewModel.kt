package com.agentphone.app.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.repository.ProviderRepository
import com.agentphone.app.domain.model.ProviderProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProvidersUiState(
    val profiles: List<ProviderProfile> = emptyList(),
    val activeId: String? = null,
)

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<ProvidersUiState> = combine(
        providers.observeProfiles(),
        settings.settings,
    ) { profiles, appSettings ->
        ProvidersUiState(profiles, appSettings.activeProviderId ?: profiles.firstOrNull()?.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProvidersUiState())

    fun setActive(id: String) {
        viewModelScope.launch { providers.setActive(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { providers.delete(id) }
    }
}
