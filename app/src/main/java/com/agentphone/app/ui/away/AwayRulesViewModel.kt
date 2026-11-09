package com.agentphone.app.ui.away

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.entity.AwayChatMode
import com.agentphone.app.data.local.entity.AwayChatRuleEntity
import com.agentphone.app.data.local.entity.AwayChatType
import com.agentphone.app.telegram.ChannelChat
import com.agentphone.app.telegram.ChannelKind
import com.agentphone.app.telegram.MessageChannel
import com.agentphone.app.telegram.TdLibClient
import com.agentphone.app.telegram.TelegramAuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AwayRulesUiState(
    val rules: List<AwayChatRuleEntity> = emptyList(),
    val chats: List<ChannelChat> = emptyList(),
    val loadingChats: Boolean = false,
    val telegramReady: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

private data class ChatPicker(
    val chats: List<ChannelChat> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class AwayRulesViewModel @Inject constructor(
    private val dao: AwayDao,
    private val channel: MessageChannel,
    private val client: TdLibClient,
) : ViewModel() {

    private val picker = MutableStateFlow(ChatPicker())

    val state: StateFlow<AwayRulesUiState> = combine(
        dao.observeRules(),
        client.authState,
        picker,
    ) { rules, auth, pick ->
        AwayRulesUiState(
            rules = rules,
            chats = pick.chats,
            loadingChats = pick.loading,
            telegramReady = auth is TelegramAuthState.Ready,
            error = pick.error,
            notice = pick.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AwayRulesUiState())

    fun clearMessages() {
        picker.value = picker.value.copy(error = null, notice = null)
    }

    fun loadChats() {
        if (picker.value.loading) return
        viewModelScope.launch {
            picker.value = picker.value.copy(loading = true, error = null, notice = null)
            runCatching { channel.chats(60) }
                .onSuccess { chats ->
                    picker.value = picker.value.copy(
                        loading = false,
                        chats = chats.filter { !it.isChannel && !it.isBot },
                    )
                }
                .onFailure { error ->
                    picker.value = picker.value.copy(
                        loading = false,
                        error = error.message ?: "Telegram is not connected.",
                    )
                }
        }
    }

    fun addChat(chat: ChannelChat) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = dao.rule(chat.id)
            dao.upsertRule(
                existing?.copy(
                    title = chat.title,
                    chatType = chat.kind,
                    updatedAt = now,
                ) ?: AwayChatRuleEntity(
                    chatId = chat.id,
                    title = chat.title,
                    chatType = chat.kind,
                    mode = AwayChatMode.DEFAULT,
                    disclosure = null,
                    sendToAi = true,
                    instructions = null,
                    addedAt = now,
                    updatedAt = now,
                ),
            )
            picker.value = picker.value.copy(notice = null)
        }
    }

    fun setMode(rule: AwayChatRuleEntity, mode: String) = update(rule) { it.copy(mode = mode) }

    fun setSendToAi(rule: AwayChatRuleEntity, value: Boolean) =
        update(rule) { it.copy(sendToAi = value) }

    fun setDisclosure(rule: AwayChatRuleEntity, value: Boolean?) =
        update(rule) { it.copy(disclosure = value) }

    fun setInstructions(rule: AwayChatRuleEntity, text: String) =
        update(rule) { it.copy(instructions = text.trim().ifBlank { null }) }

    fun remove(chatId: Long) {
        viewModelScope.launch { dao.deleteRule(chatId) }
    }

    private fun update(rule: AwayChatRuleEntity, transform: (AwayChatRuleEntity) -> AwayChatRuleEntity) {
        viewModelScope.launch {
            dao.upsertRule(transform(rule).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    companion object {
        fun chatTypeLabel(kind: String): String = when (kind) {
            ChannelKind.GROUP, AwayChatType.GROUP -> AwayChatType.GROUP
            ChannelKind.CHANNEL, AwayChatType.CHANNEL -> AwayChatType.CHANNEL
            ChannelKind.BOT, AwayChatType.BOT -> AwayChatType.BOT
            else -> AwayChatType.PRIVATE
        }
    }
}
