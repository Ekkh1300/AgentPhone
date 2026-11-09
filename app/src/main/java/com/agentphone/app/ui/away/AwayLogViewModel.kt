package com.agentphone.app.ui.away

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.R
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.telegram.AwayApprover
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AwayLogUiState(
    val entries: List<AwayDecisionEntity> = emptyList(),
    val busy: Set<String> = emptySet(),
    val message: String? = null,
)

@HiltViewModel
class AwayLogViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AwayDao,
    private val approver: AwayApprover,
) : ViewModel() {

    private val busy = MutableStateFlow<Set<String>>(emptySet())
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<AwayLogUiState> = combine(
        dao.observeRecent(),
        busy,
        message,
    ) { entries, busyIds, msg ->
        AwayLogUiState(entries = entries, busy = busyIds, message = msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AwayLogUiState())

    fun clearMessage() {
        message.value = null
    }

    fun undo(decision: AwayDecisionEntity) {
        run(decision) {
            val ok = approver.undo(decision.id)
            message.value = context.getString(
                if (ok) R.string.away_log_undone else R.string.away_approve_failed_title,
            )
        }
    }

    fun correct(decision: AwayDecisionEntity, text: String) {
        run(decision) {
            val ok = approver.correct(decision.id, text)
            message.value = context.getString(
                if (ok) R.string.away_log_corrected else R.string.away_approve_failed_title,
            )
        }
    }

    fun neverReply(decision: AwayDecisionEntity) {
        run(decision) {
            approver.neverReply(decision.chatId, decision.chatTitle, decision.chatType)
            message.value = context.getString(R.string.away_never_done)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            dao.deleteDecisions()
            message.value = context.getString(R.string.away_log_cleared)
        }
    }

    private fun run(decision: AwayDecisionEntity, block: suspend () -> Unit) {
        if (decision.id in busy.value) return
        viewModelScope.launch {
            busy.value = busy.value + decision.id
            runCatching { block() }.onFailure {
                message.value = it.message ?: context.getString(R.string.away_approve_failed_title)
            }
            busy.value = busy.value - decision.id
        }
    }
}
