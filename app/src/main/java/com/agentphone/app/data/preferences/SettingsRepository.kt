package com.agentphone.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agentphone.app.core.AppLanguage
import com.agentphone.app.data.security.SecretCipher
import com.agentphone.app.domain.model.AppSettings
import com.agentphone.app.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "agentphone_settings",
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {

    val settings: Flow<AppSettings> = dataStore.data
        .map { prefs -> prefs.toSettings() }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETE] = next.onboardingComplete
            prefs[Keys.ACTIVE_PROVIDER] = next.activeProviderId ?: ""
            prefs[Keys.THEME_MODE] = next.themeMode.name
            prefs[Keys.DYNAMIC_COLOR] = next.dynamicColor
            prefs[Keys.AMOLED] = next.amoled
            prefs[Keys.PERSIAN_DIGITS] = next.persianDigits
            prefs[Keys.LANGUAGE] = next.language
            prefs[Keys.HAPTICS] = next.haptics
            prefs[Keys.STREAM] = next.streamResponses
            prefs[Keys.AUTO_TITLE] = next.autoTitle
            prefs[Keys.MAX_STEPS] = next.maxAgentSteps
            prefs[Keys.CONTEXT_WINDOW] = next.contextWindowTokens
            prefs[Keys.MAX_OUTPUT] = next.maxOutputTokens
            prefs[Keys.TEMPERATURE] = next.temperature
            prefs[Keys.TOP_P] = next.topP
            prefs[Keys.SYSTEM_PROMPT] = next.systemPrompt
            prefs[Keys.ENABLED_TOOLS] = next.enabledTools
            prefs[Keys.TOOL_REVISION] = AppSettings.TOOL_REVISION
            prefs[Keys.ALWAYS_ALLOW] = next.alwaysAllowTools
            prefs[Keys.TTS] = next.ttsEnabled
            prefs[Keys.WEB_SEARCH_ENDPOINT] = next.webSearchEndpoint
            prefs[Keys.WEB_SEARCH_KEY] = next.webSearchApiKeyEncrypted ?: ""
            prefs[Keys.SAF_TREE_URI] = next.safTreeUri ?: ""
            prefs[Keys.ALLOW_PRIVATE_WEB] = next.allowPrivateWeb
            prefs[Keys.MEMORY_NOTES] = next.memoryNotes
            prefs[Keys.AUTO_REPLY_ENABLED] = next.autoReplyEnabled
            prefs[Keys.AUTO_REPLY_DELAY] = next.autoReplyDelayMinutes
            prefs[Keys.AUTO_REPLY_LIMIT] = next.autoReplyMaxPerDay
            prefs[Keys.AUTO_REPLY_INSTRUCTIONS] = next.autoReplyInstructions
            prefs[Keys.MCP_ENABLED] = next.mcpEnabled
            prefs[Keys.MCP_URL] = next.mcpServerUrl
            prefs[Keys.MCP_KEY] = next.mcpApiKeyEncrypted ?: ""
            prefs[Keys.LAST_CHAT] = next.lastChatId ?: ""
            prefs[Keys.TOTAL_PROMPT] = next.totalPromptTokens
            prefs[Keys.TOTAL_COMPLETION] = next.totalCompletionTokens
        }
        if (next.language != AppLanguage.get(context)) {
            AppLanguage.set(context, next.language)
        }
    }

    suspend fun setOnboardingComplete(value: Boolean) =
        update { it.copy(onboardingComplete = value) }

    suspend fun setActiveProvider(id: String?) =
        update { it.copy(activeProviderId = id) }

    suspend fun setLanguage(language: String) {
        AppLanguage.set(context, language)
        update { it.copy(language = language) }
    }

    suspend fun setLastChat(id: String?) = update { it.copy(lastChatId = id) }

    suspend fun addUsage(prompt: Int, completion: Int) = update {
        it.copy(
            totalPromptTokens = it.totalPromptTokens + prompt,
            totalCompletionTokens = it.totalCompletionTokens + completion,
        )
    }

    suspend fun setWebSearchKey(plaintext: String?) = update {
        it.copy(webSearchApiKeyEncrypted = plaintext?.takeIf(String::isNotBlank)?.let(cipher::encrypt))
    }

    suspend fun webSearchKey(): String? {
        val value = current().webSearchApiKeyEncrypted ?: return null
        if (value.isBlank()) return null
        return runCatching { cipher.decrypt(value) }.getOrNull()
    }

    suspend fun setMcpKey(plaintext: String?) = update {
        it.copy(mcpApiKeyEncrypted = plaintext?.takeIf(String::isNotBlank)?.let(cipher::encrypt))
    }

    suspend fun resetAll() {
        dataStore.edit { it.clear() }
    }

    suspend fun mcpKey(): String? {
        val value = current().mcpApiKeyEncrypted ?: return null
        if (value.isBlank()) return null
        return runCatching { cipher.decrypt(value) }.getOrNull()
    }

    private fun Preferences.toSettings(): AppSettings =
        AppSettings(
            onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
            activeProviderId = this[Keys.ACTIVE_PROVIDER]?.takeIf(String::isNotBlank),
            themeMode = ThemeMode.from(this[Keys.THEME_MODE]),
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
            amoled = this[Keys.AMOLED] ?: false,
            persianDigits = this[Keys.PERSIAN_DIGITS] ?: false,
            language = this[Keys.LANGUAGE] ?: AppLanguage.get(context),
            haptics = this[Keys.HAPTICS] ?: true,
            streamResponses = this[Keys.STREAM] ?: true,
            autoTitle = this[Keys.AUTO_TITLE] ?: true,
            maxAgentSteps = (this[Keys.MAX_STEPS] ?: 8).coerceIn(1, 30),
            contextWindowTokens = (this[Keys.CONTEXT_WINDOW] ?: 32_768).coerceIn(4_096, 2_000_000),
            maxOutputTokens = (this[Keys.MAX_OUTPUT] ?: 4_096).coerceIn(256, 128_000),
            temperature = (this[Keys.TEMPERATURE] ?: 0.7f).coerceIn(0f, 2f),
            topP = (this[Keys.TOP_P] ?: 1f).coerceIn(0.01f, 1f),
            systemPrompt = this[Keys.SYSTEM_PROMPT] ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
            enabledTools = this[Keys.ENABLED_TOOLS]?.let { stored ->
                val revision = this[Keys.TOOL_REVISION] ?: 1
                buildSet {
                    addAll(stored)
                    if (revision < 2) addAll(AppSettings.TOOLS_ADDED_IN_REVISION_2)
                    if (revision < 3) addAll(AppSettings.TOOLS_ADDED_IN_REVISION_3)
                    if (revision < 4) addAll(AppSettings.TOOLS_ADDED_IN_REVISION_4)
                }
            } ?: AppSettings.DEFAULT_ENABLED_TOOLS,
            alwaysAllowTools = this[Keys.ALWAYS_ALLOW] ?: emptySet(),
            ttsEnabled = this[Keys.TTS] ?: false,
            webSearchEndpoint = this[Keys.WEB_SEARCH_ENDPOINT]
                ?: "https://api.tavily.com/search",
            webSearchApiKeyEncrypted = this[Keys.WEB_SEARCH_KEY]?.takeIf(String::isNotBlank),
            safTreeUri = this[Keys.SAF_TREE_URI]?.takeIf(String::isNotBlank),
            allowPrivateWeb = this[Keys.ALLOW_PRIVATE_WEB] ?: false,
            memoryNotes = this[Keys.MEMORY_NOTES] ?: "",
            autoReplyEnabled = this[Keys.AUTO_REPLY_ENABLED] ?: false,
            autoReplyDelayMinutes = (this[Keys.AUTO_REPLY_DELAY] ?: 30).coerceIn(1, 180),
            autoReplyMaxPerDay = (this[Keys.AUTO_REPLY_LIMIT] ?: 5).coerceIn(1, 20),
            autoReplyInstructions = this[Keys.AUTO_REPLY_INSTRUCTIONS] ?: "",
            mcpEnabled = this[Keys.MCP_ENABLED] ?: false,
            mcpServerUrl = this[Keys.MCP_URL] ?: "",
            mcpApiKeyEncrypted = this[Keys.MCP_KEY]?.takeIf(String::isNotBlank),
            lastChatId = this[Keys.LAST_CHAT]?.takeIf(String::isNotBlank),
            totalPromptTokens = this[Keys.TOTAL_PROMPT] ?: 0L,
            totalCompletionTokens = this[Keys.TOTAL_COMPLETION] ?: 0L,
        )

    private object Keys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AMOLED = booleanPreferencesKey("amoled")
        val PERSIAN_DIGITS = booleanPreferencesKey("persian_digits")
        val LANGUAGE = stringPreferencesKey("language")
        val HAPTICS = booleanPreferencesKey("haptics")
        val STREAM = booleanPreferencesKey("stream")
        val AUTO_TITLE = booleanPreferencesKey("auto_title")
        val MAX_STEPS = intPreferencesKey("max_agent_steps")
        val CONTEXT_WINDOW = intPreferencesKey("context_window")
        val MAX_OUTPUT = intPreferencesKey("max_output")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val ENABLED_TOOLS = stringSetPreferencesKey("enabled_tools")
        val ALWAYS_ALLOW = stringSetPreferencesKey("always_allow_tools")
        val TTS = booleanPreferencesKey("tts_enabled")
        val WEB_SEARCH_ENDPOINT = stringPreferencesKey("web_search_endpoint")
        val WEB_SEARCH_KEY = stringPreferencesKey("web_search_key_enc")
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val ALLOW_PRIVATE_WEB = booleanPreferencesKey("allow_private_web")
        val MEMORY_NOTES = stringPreferencesKey("memory_notes")
        val AUTO_REPLY_ENABLED = booleanPreferencesKey("auto_reply_enabled")
        val AUTO_REPLY_DELAY = intPreferencesKey("auto_reply_delay_minutes")
        val AUTO_REPLY_LIMIT = intPreferencesKey("auto_reply_max_per_day")
        val AUTO_REPLY_INSTRUCTIONS = stringPreferencesKey("auto_reply_instructions")
        val MCP_ENABLED = booleanPreferencesKey("mcp_enabled")
        val MCP_URL = stringPreferencesKey("mcp_url")
        val MCP_KEY = stringPreferencesKey("mcp_key_enc")
        val LAST_CHAT = stringPreferencesKey("last_chat")
        val TOTAL_PROMPT = longPreferencesKey("total_prompt_tokens")
        val TOTAL_COMPLETION = longPreferencesKey("total_completion_tokens")
        val TOOL_REVISION = intPreferencesKey("tool_revision")
    }
}

