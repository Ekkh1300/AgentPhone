package com.agentphone.app.ui.away

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.entity.AwayChatRuleEntity
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.repository.ProviderRepository
import com.agentphone.app.telegram.AwayResponder
import com.agentphone.app.telegram.AwayRuntime
import com.agentphone.app.telegram.AwaySafety
import com.agentphone.app.telegram.AwaySettings
import com.agentphone.app.telegram.AwaySettingsRepository
import com.agentphone.app.telegram.ChannelChat
import com.agentphone.app.telegram.ChannelKind
import com.agentphone.app.telegram.ChannelMessage
import com.agentphone.app.telegram.ServiceHealth
import com.agentphone.app.telegram.TdLibClient
import com.agentphone.app.telegram.TelegramAuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

data class SimulatorState(
    val running: Boolean = false,
    val output: String? = null,
    val error: String? = null,
)

data class AwayUiState(
    val loading: Boolean = true,
    val settings: AwaySettings = AwaySettings(),
    val telegramReady: Boolean = false,
    val providerName: String? = null,
    val providerReady: Boolean = false,
    val drafts: List<AwayDecisionEntity> = emptyList(),
    val recent: List<AwayDecisionEntity> = emptyList(),
    val rules: List<AwayChatRuleEntity> = emptyList(),
    val serviceAlive: Boolean = false,
    val lastBeat: Long = 0L,
    val simulator: SimulatorState = SimulatorState(),
)

private data class Readiness(
    val telegramReady: Boolean,
    val providerName: String?,
    val providerReady: Boolean,
)

private data class Health(
    val running: Boolean,
    val lastBeat: Long,
)

@HiltViewModel
class AwayViewModel @Inject constructor(
    private val awaySettings: AwaySettingsRepository,
    private val runtime: AwayRuntime,
    private val dao: AwayDao,
    private val client: TdLibClient,
    private val providers: ProviderRepository,
    private val appSettings: SettingsRepository,
    private val responder: AwayResponder,
) : ViewModel() {

    private val simulator = MutableStateFlow(SimulatorState())

    private val readiness = combine(
        client.authState,
        providers.observeProfiles(),
        appSettings.settings,
    ) { auth, profiles, app ->
        val profile = profiles.firstOrNull { it.id == app.activeProviderId } ?: profiles.firstOrNull()
        Readiness(
            telegramReady = auth is TelegramAuthState.Ready,
            providerName = profile?.name,
            providerReady = profile != null && profile.defaultModel.isNotBlank(),
        )
    }

    private val health = combine(ServiceHealth.running, ServiceHealth.lastBeat) { running, lastBeat ->
        Health(running, lastBeat)
    }

    private val data = combine(
        dao.observeDrafts(),
        dao.observeRecent(40),
        dao.observeRules(),
    ) { drafts, recent, rules -> Triple(drafts, recent, rules) }

    val state: StateFlow<AwayUiState> = combine(
        awaySettings.settings,
        readiness,
        data,
        health,
        simulator,
    ) { settings, ready, rows, h, sim ->
        AwayUiState(
            loading = false,
            settings = settings,
            telegramReady = ready.telegramReady,
            providerName = ready.providerName,
            providerReady = ready.providerReady,
            drafts = rows.first,
            recent = rows.second,
            rules = rows.third,
            serviceAlive = h.running,
            lastBeat = h.lastBeat,
            simulator = sim,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AwayUiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            awaySettings.setEnabled(enabled)
            runtime.sync()
        }
    }

    fun setDelayMinutes(minutes: Int) {
        viewModelScope.launch { awaySettings.setDelayMinutes(minutes) }
    }

    fun setConfidence(threshold: Float) {
        viewModelScope.launch { awaySettings.setConfidence(threshold) }
    }

    fun setPerChatCap(cap: Int) {
        viewModelScope.launch { awaySettings.setPerChatCap(cap) }
    }

    fun setGlobalCap(cap: Int) {
        viewModelScope.launch { awaySettings.setGlobalCap(cap) }
    }

    fun setCooldown(minutes: Int) {
        viewModelScope.launch { awaySettings.setCooldown(minutes) }
    }

    fun setScheduleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = awaySettings.current()
            awaySettings.setSchedule(
                enabled,
                current.scheduleStartMinute,
                current.scheduleEndMinute,
                current.scheduleDays,
            )
        }
    }

    fun setScheduleStart(minute: Int) {
        viewModelScope.launch {
            val current = awaySettings.current()
            awaySettings.setSchedule(
                current.scheduleEnabled,
                minute,
                current.scheduleEndMinute,
                current.scheduleDays,
            )
        }
    }

    fun setScheduleEnd(minute: Int) {
        viewModelScope.launch {
            val current = awaySettings.current()
            awaySettings.setSchedule(
                current.scheduleEnabled,
                current.scheduleStartMinute,
                minute,
                current.scheduleDays,
            )
        }
    }

    fun toggleDay(day: DayOfWeek) {
        viewModelScope.launch {
            val current = awaySettings.current()
            val bit = 1 shl (day.value - 1)
            val days = current.scheduleDays xor bit
            awaySettings.setSchedule(
                current.scheduleEnabled,
                current.scheduleStartMinute,
                current.scheduleEndMinute,
                days,
            )
        }
    }

    fun setPersona(text: String) {
        viewModelScope.launch { awaySettings.setPersona(text) }
    }

    fun setNicknames(text: String) {
        viewModelScope.launch { awaySettings.setNicknames(text) }
    }

    fun setDisclosure(enabled: Boolean, text: String) {
        viewModelScope.launch { awaySettings.setDisclosure(enabled, text) }
    }

    fun setSummaryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            awaySettings.setSummaryEnabled(enabled)
            runtime.sync()
        }
    }

    fun resetErrorStreak() {
        viewModelScope.launch { awaySettings.resetErrorStreak() }
    }

    fun simulate(text: String, group: Boolean) {
        if (text.isBlank()) return
        viewModelScope.launch {
            simulator.value = SimulatorState(running = true)
            simulator.value = try {
                val now = System.currentTimeMillis()
                val chat = ChannelChat(
                    id = 0L,
                    title = "Simulator",
                    kind = if (group) ChannelKind.GROUP else ChannelKind.PRIVATE,
                    isContact = true,
                    canSend = true,
                )
                val trigger = ChannelMessage(
                    id = 1L,
                    chatId = 0L,
                    senderId = 1L,
                    senderName = "Simulator",
                    text = text,
                    date = now,
                    outgoing = false,
                    isBot = false,
                    hasAttachment = false,
                    hasLink = false,
                    forumTopicId = null,
                    replyToMessageId = null,
                    mentionedMe = group,
                    repliesToMe = false,
                    calledByName = false,
                    senderIsContact = true,
                )
                val decision = responder.decide(
                    chat = chat,
                    context = listOf(trigger),
                    trigger = trigger,
                    rule = null,
                    localRisks = AwaySafety.scanIncoming(text),
                    forcedDraft = true,
                    freeBusy = null,
                )
                SimulatorState(output = decision.toJsonText())
            } catch (error: Throwable) {
                SimulatorState(error = error.message ?: "Simulation failed")
            }
        }
    }

    fun clearSimulator() {
        simulator.value = SimulatorState()
    }
}
