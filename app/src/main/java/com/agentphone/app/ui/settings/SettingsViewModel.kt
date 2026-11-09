package com.agentphone.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.data.backup.BackupManager
import com.agentphone.app.data.local.dao.AutoReplyDao
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.repository.AuditRepository
import com.agentphone.app.data.repository.ChatRepository
import com.agentphone.app.data.repository.ProviderRepository
import com.agentphone.app.domain.model.AppSettings
import com.agentphone.app.domain.model.ProviderProfile
import com.agentphone.app.domain.model.ThemeMode
import com.agentphone.app.autoreply.AutoReplyScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val providers: List<ProviderProfile> = emptyList(),
    val hasWebSearchKey: Boolean = false,
    val hasMcpKey: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providers: ProviderRepository,
    private val chats: ChatRepository,
    private val audit: AuditRepository,
    private val backup: BackupManager,
    private val autoReplyDao: AutoReplyDao,
    private val autoReplies: AutoReplyScheduler,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        providers.observeProfiles(),
    ) { settings, profiles ->
        SettingsUiState(
            loading = false,
            settings = settings,
            providers = profiles,
            hasWebSearchKey = !settings.webSearchApiKeyEncrypted.isNullOrBlank(),
            hasMcpKey = !settings.mcpApiKeyEncrypted.isNullOrBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    fun setDynamicColor(value: Boolean) = update { it.copy(dynamicColor = value) }
    fun setAmoled(value: Boolean) = update { it.copy(amoled = value) }
    fun setHaptics(value: Boolean) = update { it.copy(haptics = value) }
    fun setPersianDigits(value: Boolean) = update { it.copy(persianDigits = value) }
    fun setAutoTitle(value: Boolean) = update { it.copy(autoTitle = value) }
    fun setStreaming(value: Boolean) = update { it.copy(streamResponses = value) }
    fun setMaxSteps(value: Int) = update { it.copy(maxAgentSteps = value.coerceIn(1, 30)) }
    fun setTemperature(value: Float) = update { it.copy(temperature = value) }
    fun setMaxOutput(value: Int) = update { it.copy(maxOutputTokens = value.coerceIn(256, 128_000)) }
    fun setContextWindow(value: Int) = update { it.copy(contextWindowTokens = value.coerceIn(4_096, 2_000_000)) }
    fun setSystemPrompt(value: String) = update { it.copy(systemPrompt = value) }
    fun setMemory(value: String) = update { it.copy(memoryNotes = value.take(16_000)) }
    fun setAllowPrivateWeb(value: Boolean) = update { it.copy(allowPrivateWeb = value) }
    fun setWebSearchEndpoint(value: String) = update { it.copy(webSearchEndpoint = value) }
    fun setMcpEnabled(value: Boolean) = update { it.copy(mcpEnabled = value) }
    fun setMcpUrl(value: String) = update { it.copy(mcpServerUrl = value) }
    fun setLanguage(tag: String) = update { it.copy(language = tag) }
    fun setAutoReplyDelay(minutes: Int) = update { it.copy(autoReplyDelayMinutes = minutes.coerceIn(1, 180)) }
    fun setAutoReplyLimit(value: Int) = update { it.copy(autoReplyMaxPerDay = value.coerceIn(1, 20)) }
    fun setAutoReplyInstructions(value: String) = update { it.copy(autoReplyInstructions = value.take(8_000)) }

    fun setAutoReplyEnabled(enabled: Boolean) {
        update { it.copy(autoReplyEnabled = enabled) }
        if (!enabled) {
            viewModelScope.launch { autoReplies.cancelAllPending() }
        }
    }

    fun saveWebSearchKey(value: String) {
        viewModelScope.launch { settingsRepository.setWebSearchKey(value) }
    }

    fun saveMcpKey(value: String) {
        viewModelScope.launch { settingsRepository.setMcpKey(value) }
    }

    fun clearAllData() {
        viewModelScope.launch {
            chats.deleteAllChats()
            providers.deleteAll()
            audit.clear()
            autoReplyDao.deleteAll()
            settingsRepository.resetAll()
        }
    }

    suspend fun exportBackup(): String = backup.exportJson()

    suspend fun importBackup(raw: String): Int = backup.importJson(raw)

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
