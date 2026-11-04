package com.agentphone.app.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.repository.ChatRepository
import com.agentphone.app.data.repository.ProviderRepository
import com.agentphone.app.domain.model.Chat
import com.agentphone.app.platform.ShareDraftStore
import com.agentphone.app.platform.SharedPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val chats: ChatRepository,
    private val providers: ProviderRepository,
    private val settings: SettingsRepository,
    private val draftStore: ShareDraftStore,
) : ViewModel() {

    private val _openChat = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openChat = _openChat.asSharedFlow()

    private var handling = false

    fun handle(payload: SharedPayload) {
        if (handling) return
        handling = true
        viewModelScope.launch {
            val appSettings = settings.current()
            val provider = providers.activeProfile()
            val chat = chats.createChat(
                title = Chat.UNTITLED,
                settings = appSettings,
                providerId = provider?.id,
                model = provider?.defaultModel,
            )
            draftStore.put(chat.id, payload)
            _openChat.emit(chat.id)
            handling = false
        }
    }

    fun startNewChat() {
        viewModelScope.launch {
            val appSettings = settings.current()
            val provider = providers.activeProfile()
            val chat = chats.createChat(
                title = Chat.UNTITLED,
                settings = appSettings,
                providerId = provider?.id,
                model = provider?.defaultModel,
            )
            _openChat.emit(chat.id)
        }
    }
}
