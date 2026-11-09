package com.agentphone.app.telegram

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the away-mode engine needs to decide whether and how to answer. Secrets (api hash,
 * database key) live in [TelegramCredentialStore]; this file only holds plain preferences.
 */
data class AwaySettings(
    val enabled: Boolean = false,
    val delayMinutes: Int = 30,
    val confidenceThreshold: Float = 0.75f,
    val perChatDailyCap: Int = 3,
    val globalDailyCap: Int = 20,
    val cooldownMinutes: Int = 15,
    val scheduleEnabled: Boolean = false,
    val scheduleStartMinute: Int = 0,
    val scheduleEndMinute: Int = 8 * 60,
    val scheduleDays: Int = ALL_DAYS,
    val disclosureEnabled: Boolean = true,
    val disclosureText: String = "",
    val persona: String = "",
    /** Comma separated names the owner is called in group chats, used for addressed detection. */
    val nicknames: String = "",
    val errorStreak: Int = 0,
    val disabledReason: String? = null,
    val autoEnableWithDnd: Boolean = false,
    val summaryEnabled: Boolean = true,
    val summaryLastAt: Long = 0L,
) {
    /** Away mode may send only while the configured window is open. */
    fun insideSchedule(now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (!scheduleEnabled) return true
        val dayBit = 1 shl (now.dayOfWeek.value - 1)
        if (scheduleDays and dayBit == 0) return false
        val minute = now.hour * 60 + now.minute
        val start = scheduleStartMinute.coerceIn(0, 24 * 60 - 1)
        val end = scheduleEndMinute.coerceIn(0, 24 * 60)
        if (start == end) return true
        return if (start < end) minute in start until end else minute >= start || minute < end
    }

    fun dayEnabled(day: DayOfWeek): Boolean = scheduleDays and (1 shl (day.value - 1)) != 0

    companion object {
        const val ALL_DAYS = 0b1111111
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val MIN_DELAY_MINUTES = 5
        const val MAX_DELAY_MINUTES = 360
        const val DEFAULT_DISCLOSURE_EN = " (auto-reply)"
        const val DEFAULT_DISCLOSURE_FA = " (پاسخ خودکار)"
    }
}

@Singleton
class AwaySettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<AwaySettings> = dataStore.data
        .map { prefs -> prefs.toAwaySettings() }
        .distinctUntilChanged()

    /** Subscribed by the foreground service: a flip to false must cancel every pending job. */
    val enabledFlow: Flow<Boolean> = settings.map { it.enabled }.distinctUntilChanged()

    suspend fun current(): AwaySettings = settings.first()

    suspend fun update(transform: (AwaySettings) -> AwaySettings) {
        dataStore.edit { prefs ->
            val next = prefs.toAwaySettings()
            val merged = transform(next)
            prefs[Keys.ENABLED] = merged.enabled
            prefs[Keys.DELAY] = merged.delayMinutes.coerceIn(
                AwaySettings.MIN_DELAY_MINUTES,
                AwaySettings.MAX_DELAY_MINUTES,
            )
            prefs[Keys.CONFIDENCE] = merged.confidenceThreshold.coerceIn(0f, 1f)
            prefs[Keys.CHAT_CAP] = merged.perChatDailyCap.coerceIn(1, 20)
            prefs[Keys.GLOBAL_CAP] = merged.globalDailyCap.coerceIn(1, 100)
            prefs[Keys.COOLDOWN] = merged.cooldownMinutes.coerceIn(0, 240)
            prefs[Keys.SCHEDULE_ENABLED] = merged.scheduleEnabled
            prefs[Keys.SCHEDULE_START] = merged.scheduleStartMinute.coerceIn(0, 24 * 60 - 1)
            prefs[Keys.SCHEDULE_END] = merged.scheduleEndMinute.coerceIn(0, 24 * 60)
            prefs[Keys.SCHEDULE_DAYS] = merged.scheduleDays and AwaySettings.ALL_DAYS
            prefs[Keys.DISCLOSURE_ENABLED] = merged.disclosureEnabled
            prefs[Keys.DISCLOSURE_TEXT] = merged.disclosureText
            prefs[Keys.PERSONA] = merged.persona
            prefs[Keys.NICKNAMES] = merged.nicknames
            prefs[Keys.ERROR_STREAK] = merged.errorStreak
            prefs[Keys.DISABLED_REASON] = merged.disabledReason ?: ""
            prefs[Keys.AUTO_ENABLE_DND] = merged.autoEnableWithDnd
            prefs[Keys.SUMMARY_ENABLED] = merged.summaryEnabled
            prefs[Keys.SUMMARY_LAST_AT] = merged.summaryLastAt
        }
    }

    suspend fun setEnabled(enabled: Boolean) = update {
        it.copy(enabled = enabled, disabledReason = null, errorStreak = if (enabled) 0 else it.errorStreak)
    }

    suspend fun setDelayMinutes(minutes: Int) = update { it.copy(delayMinutes = minutes) }
    suspend fun setConfidence(threshold: Float) = update { it.copy(confidenceThreshold = threshold) }
    suspend fun setPerChatCap(cap: Int) = update { it.copy(perChatDailyCap = cap) }
    suspend fun setGlobalCap(cap: Int) = update { it.copy(globalDailyCap = cap) }
    suspend fun setCooldown(minutes: Int) = update { it.copy(cooldownMinutes = minutes) }
    suspend fun setSchedule(enabled: Boolean, startMinute: Int, endMinute: Int, days: Int) = update {
        it.copy(
            scheduleEnabled = enabled,
            scheduleStartMinute = startMinute,
            scheduleEndMinute = endMinute,
            scheduleDays = days,
        )
    }

    suspend fun setDisclosure(enabled: Boolean, text: String) = update {
        it.copy(disclosureEnabled = enabled, disclosureText = text)
    }

    suspend fun setPersona(text: String) = update { it.copy(persona = text) }
    suspend fun setNicknames(text: String) = update { it.copy(nicknames = text) }
    suspend fun setAutoEnableWithDnd(value: Boolean) = update { it.copy(autoEnableWithDnd = value) }
    suspend fun setSummaryEnabled(value: Boolean) = update { it.copy(summaryEnabled = value) }

    suspend fun markSummary(time: Long = System.currentTimeMillis()) =
        update { it.copy(summaryLastAt = time) }

    /** Called after a successful send; a clean run resets the auto-disable streak. */
    suspend fun recordSuccess() = update {
        if (it.errorStreak == 0) it else it.copy(errorStreak = 0)
    }

    /**
     * Tracks consecutive provider/transport failures. At [AwaySettings.MAX_CONSECUTIVE_ERRORS] the
     * master switch is turned off so a broken setup never keeps sending garbage.
     */
    suspend fun recordFailure(reason: String): AwaySettings = mutex.withLock {
        var result: AwaySettings? = null
        update { current ->
            val streak = current.errorStreak + 1
            val disabled = streak >= AwaySettings.MAX_CONSECUTIVE_ERRORS
            current.copy(
                errorStreak = streak,
                enabled = if (disabled) false else current.enabled,
                disabledReason = if (disabled) reason.take(200) else current.disabledReason,
            ).also { result = it }
        }
        result ?: current()
    }

    suspend fun resetErrorStreak() = update {
        it.copy(errorStreak = 0, disabledReason = null)
    }

    /** Full reset used by the connection screen when local Telegram data is deleted. */
    suspend fun clear() {
        dataStore.edit { prefs ->
            Keys.ALL.forEach { prefs.remove(it) }
        }
    }

    private fun Preferences.toAwaySettings(): AwaySettings = AwaySettings(
        enabled = this[Keys.ENABLED] ?: false,
        delayMinutes = (this[Keys.DELAY] ?: 30).coerceIn(
            AwaySettings.MIN_DELAY_MINUTES,
            AwaySettings.MAX_DELAY_MINUTES,
        ),
        confidenceThreshold = (this[Keys.CONFIDENCE] ?: 0.75f).coerceIn(0f, 1f),
        perChatDailyCap = (this[Keys.CHAT_CAP] ?: 3).coerceIn(1, 20),
        globalDailyCap = (this[Keys.GLOBAL_CAP] ?: 20).coerceIn(1, 100),
        cooldownMinutes = (this[Keys.COOLDOWN] ?: 15).coerceIn(0, 240),
        scheduleEnabled = this[Keys.SCHEDULE_ENABLED] ?: false,
        scheduleStartMinute = (this[Keys.SCHEDULE_START] ?: 0).coerceIn(0, 24 * 60 - 1),
        scheduleEndMinute = (this[Keys.SCHEDULE_END] ?: 8 * 60).coerceIn(0, 24 * 60),
        scheduleDays = (this[Keys.SCHEDULE_DAYS] ?: AwaySettings.ALL_DAYS) and AwaySettings.ALL_DAYS,
        disclosureEnabled = this[Keys.DISCLOSURE_ENABLED] ?: true,
        disclosureText = this[Keys.DISCLOSURE_TEXT] ?: "",
        persona = this[Keys.PERSONA] ?: "",
        nicknames = this[Keys.NICKNAMES] ?: "",
        errorStreak = this[Keys.ERROR_STREAK] ?: 0,
        disabledReason = this[Keys.DISABLED_REASON]?.takeIf { it.isNotBlank() },
        autoEnableWithDnd = this[Keys.AUTO_ENABLE_DND] ?: false,
        summaryEnabled = this[Keys.SUMMARY_ENABLED] ?: true,
        summaryLastAt = this[Keys.SUMMARY_LAST_AT] ?: 0L,
    )

    private companion object {
        val mutex = Mutex()

        object Keys {
            val ENABLED = booleanPreferencesKey("away_enabled")
            val DELAY = intPreferencesKey("away_delay_minutes")
            val CONFIDENCE = floatPreferencesKey("away_confidence")
            val CHAT_CAP = intPreferencesKey("away_per_chat_cap")
            val GLOBAL_CAP = intPreferencesKey("away_global_cap")
            val COOLDOWN = intPreferencesKey("away_cooldown_minutes")
            val SCHEDULE_ENABLED = booleanPreferencesKey("away_schedule_enabled")
            val SCHEDULE_START = intPreferencesKey("away_schedule_start")
            val SCHEDULE_END = intPreferencesKey("away_schedule_end")
            val SCHEDULE_DAYS = intPreferencesKey("away_schedule_days")
            val DISCLOSURE_ENABLED = booleanPreferencesKey("away_disclosure_enabled")
            val DISCLOSURE_TEXT = stringPreferencesKey("away_disclosure_text")
            val PERSONA = stringPreferencesKey("away_persona")
            val NICKNAMES = stringPreferencesKey("away_nicknames")
            val ERROR_STREAK = intPreferencesKey("away_error_streak")
            val DISABLED_REASON = stringPreferencesKey("away_disabled_reason")
            val AUTO_ENABLE_DND = booleanPreferencesKey("away_auto_enable_dnd")
            val SUMMARY_ENABLED = booleanPreferencesKey("away_summary_enabled")
            val SUMMARY_LAST_AT = longPreferencesKey("away_summary_last_at")

            val ALL = setOf(
                ENABLED, DELAY, CONFIDENCE, CHAT_CAP, GLOBAL_CAP, COOLDOWN,
                SCHEDULE_ENABLED, SCHEDULE_START, SCHEDULE_END, SCHEDULE_DAYS,
                DISCLOSURE_ENABLED, DISCLOSURE_TEXT, PERSONA, NICKNAMES, ERROR_STREAK,
                DISABLED_REASON, AUTO_ENABLE_DND, SUMMARY_ENABLED, SUMMARY_LAST_AT,
            )
        }
    }
}
