package com.agentphone.app.ui.chats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.R
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.repository.ChatRepository
import com.agentphone.app.data.repository.ProviderRepository
import com.agentphone.app.domain.model.AppSettings
import com.agentphone.app.domain.model.Chat
import com.agentphone.app.domain.model.ChatSummary
import com.agentphone.app.domain.model.ProviderProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ChatsEvent {
    data class OpenChat(val chatId: String) : ChatsEvent
    data class Message(val text: String) : ChatsEvent
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val chats: ChatRepository,
    private val providers: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val _events = MutableSharedFlow<ChatsEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    val searchQuery: StateFlow<String> = query

    val chatList: StateFlow<List<ChatSummary>> = query
        .debounce(120)
        .flatMapLatest { chats.observeChats(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val providerList: StateFlow<List<ProviderProfile>> = providers.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        query.value = value
    }

    fun newChat() {
        viewModelScope.launch {
            val appSettings = settingsRepository.current()
            val provider = providers.activeProfile()
            val chat = chats.createChat(
                title = Chat.UNTITLED,
                settings = appSettings,
                providerId = provider?.id,
                model = provider?.defaultModel,
            )
            settingsRepository.setLastChat(chat.id)
            _events.emit(ChatsEvent.OpenChat(chat.id))
        }
    }

    fun deleteChat(chat: ChatSummary) {
        viewModelScope.launch {
            chats.deleteChat(chat.chat.id)
            _events.emit(ChatsEvent.Message(context.getString(R.string.chat_deleted)))
        }
    }

    fun renameChat(chatId: String, title: String) {
        viewModelScope.launch { chats.renameChat(chatId, title) }
    }

    fun setPinned(chatId: String, pinned: Boolean) {
        viewModelScope.launch { chats.setPinned(chatId, pinned) }
    }

    fun setTag(chatId: String, tag: String?) {
        viewModelScope.launch { chats.setTag(chatId, tag) }
    }
}
