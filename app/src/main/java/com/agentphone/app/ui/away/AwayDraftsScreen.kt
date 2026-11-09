package com.agentphone.app.ui.away

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.R
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.data.local.entity.AwayDecisionStatus
import com.agentphone.app.ui.components.EmptyState
import com.agentphone.app.ui.components.Pill
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AwayDraftsScreen(
    onBack: () -> Unit,
    viewModel: AwayDraftsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.away_drafts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.drafts.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Inbox,
                title = stringResource(R.string.away_drafts_empty),
                body = stringResource(R.string.away_drafts_empty_body),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.drafts, key = { it.id }) { decision ->
                    DraftCard(
                        decision = decision,
                        busy = decision.id in state.busy,
                        onApprove = { viewModel.approve(decision) },
                        onReject = { viewModel.reject(decision) },
                        onEdit = { editing = decision.id },
                        onNever = { viewModel.neverReply(decision) },
                    )
                }
            }
        }
    }

    val editingDecision = state.drafts.firstOrNull { it.id == editing }
    if (editingDecision != null) {
        var text by remember(editingDecision.id) {
            mutableStateOf(editingDecision.replyText.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.away_draft_edit)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.correct(editingDecision, text)
                        editing = null
                    },
                ) { Text(stringResource(R.string.away_log_correct_send)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun DraftCard(
    decision: AwayDecisionEntity,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
    onNever: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        decision.chatTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        decision.senderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (decision.status == AwayDecisionStatus.ESCALATED) {
                    Pill(
                        stringResource(R.string.away_status_escalated),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.away_draft_incoming),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                decision.incomingText,
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.away_draft_reply),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                decision.replyText?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.away_draft_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            decision.reason?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.away_draft_reason, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(
                    R.string.away_draft_confidence,
                    (decision.confidence * 100).roundToInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val risks = risksOf(decision.risksJson)
            if (risks.isNotEmpty()) {
                Text(
                    stringResource(R.string.away_draft_risks, risks.joinToString(", ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!decision.replyText.isNullOrBlank()) {
                        Button(onClick = onApprove) {
                            Text(stringResource(R.string.away_draft_approve))
                        }
                    }
                    OutlinedButton(onClick = onEdit) {
                        Text(stringResource(R.string.away_draft_edit))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onReject) {
                        Text(stringResource(R.string.away_draft_reject))
                    }
                    TextButton(onClick = onNever) {
                        Text(stringResource(R.string.away_draft_never))
                    }
                }
            }
        }
    }
}

private val risksJson = Json { ignoreUnknownKeys = true }

private fun risksOf(raw: String): List<String> = runCatching {
    risksJson.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
}.getOrDefault(emptyList())
