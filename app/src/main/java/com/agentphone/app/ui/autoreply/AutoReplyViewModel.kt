package com.agentphone.app.ui.autoreply

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.data.local.dao.AutoReplyDao
import com.agentphone.app.data.local.entity.AutoReplyEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AutoReplyUiState(
    val loading: Boolean = true,
    val entries: List<AutoReplyEntity> = emptyList(),
)

@HiltViewModel
class AutoReplyViewModel @Inject constructor(
    private val dao: AutoReplyDao,
) : ViewModel() {

    val state: StateFlow<AutoReplyUiState> = dao.observeRecent()
        .map { AutoReplyUiState(loading = false, entries = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoReplyUiState())

    fun clear() {
        viewModelScope.launch { dao.deleteAll() }
    }
}
