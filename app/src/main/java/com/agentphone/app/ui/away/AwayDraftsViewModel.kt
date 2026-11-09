package com.agentphone.app.ui.away

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.R
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.telegram.AwayApprovalResult
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

data class AwayDraftsUiState(
    val drafts: List<AwayDecisionEntity> = emptyList(),
    val busy: Set<String> = emptySet(),
    val message: String? = null,
)

@HiltViewModel
class AwayDraftsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AwayDao,
    private val approver: AwayApprover,
) : ViewModel() {

    private val busy = MutableStateFlow<Set<String>>(emptySet())
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<AwayDraftsUiState> = combine(
        dao.observeDrafts(),
        busy,
        message,
    ) { drafts, busyIds, msg ->
        AwayDraftsUiState(drafts = drafts, busy = busyIds, message = msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AwayDraftsUiState())

    fun clearMessage() {
        message.value = null
    }

    fun approve(decision: AwayDecisionEntity) {
        run(decision) {
            when (val result = approver.approve(decision.id)) {
                is AwayApprovalResult.Sent ->
                    message.value = context.getString(R.string.away_sent_to_chat, decision.chatTitle)
                is AwayApprovalResult.Failed ->
                    message.value = "${context.getString(R.string.away_approve_failed_title)}: ${result.reason}"
            }
        }
    }

    fun reject(decision: AwayDecisionEntity) {
        run(decision) {
            approver.reject(decision.id)
            message.value = context.getString(R.string.away_draft_rejected)
        }
    }

    fun correct(decision: AwayDecisionEntity, text: String) {
        run(decision) {
            val ok = approver.correct(decision.id, text)
            message.value = context.getString(
                if (ok) R.string.away_draft_saved else R.string.away_approve_failed_title,
            )
        }
    }

    fun neverReply(decision: AwayDecisionEntity) {
        run(decision) {
            approver.neverReply(decision.chatId, decision.chatTitle, decision.chatType)
            message.value = context.getString(R.string.away_never_done)
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
