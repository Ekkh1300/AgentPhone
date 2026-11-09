package com.agentphone.app.ui.away

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.R
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.data.local.entity.AwayDecisionStatus
import com.agentphone.app.ui.components.EmptyState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AwayLogScreen(
    onBack: () -> Unit,
    viewModel: AwayLogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
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
                title = { Text(stringResource(R.string.away_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (state.entries.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.away_log_clear),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.away_log_empty),
                body = stringResource(R.string.away_log_empty_body),
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    LogCard(
                        entry = entry,
                        busy = entry.id in state.busy,
                        onUndo = { viewModel.undo(entry) },
                        onEdit = { editing = entry.id },
                        onNever = { viewModel.neverReply(entry) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.away_log_clear_title)) },
            text = { Text(stringResource(R.string.away_log_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearAll()
                    },
                ) { Text(stringResource(R.string.away_log_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val editingEntry = state.entries.firstOrNull { it.id == editing }
    if (editingEntry != null) {
        var text by remember(editingEntry.id) {
            mutableStateOf(editingEntry.replyText.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.away_log_correct_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.away_log_correct_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.correct(editingEntry, text)
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
private fun LogCard(
    entry: AwayDecisionEntity,
    busy: Boolean,
    onUndo: () -> Unit,
    onEdit: () -> Unit,
    onNever: () -> Unit,
) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.chatTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${entry.senderName} · ${formatTime(entry.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(entry.status)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.away_draft_incoming),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                entry.incomingText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.incomingText.length > 140) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.away_log_hide_incoming
                            else R.string.away_log_show_incoming,
                        ),
                    )
                }
            }
            entry.replyText?.takeIf { it.isNotBlank() }?.let { reply ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.away_draft_reply),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(reply, style = MaterialTheme.typography.bodyMedium)
            }
            entry.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.away_draft_reason, reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.status == AwayDecisionStatus.SENT ||
                        entry.status == AwayDecisionStatus.DRAFT ||
                        entry.status == AwayDecisionStatus.ESCALATED
                    ) {
                        TextButton(onClick = onUndo) {
                            Text(stringResource(R.string.away_log_undo))
                        }
                    }
                    if (entry.status == AwayDecisionStatus.SENT ||
                        entry.status == AwayDecisionStatus.DRAFT
                    ) {
                        TextButton(onClick = onEdit) {
                            Text(stringResource(R.string.away_log_correct))
                        }
                    }
                    TextButton(onClick = onNever) {
                        Text(stringResource(R.string.away_draft_never))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (container, content, label) = when (status) {
        AwayDecisionStatus.SENT -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            R.string.away_status_sent,
        )
        AwayDecisionStatus.DRAFT -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            R.string.away_status_draft,
        )
        AwayDecisionStatus.ESCALATED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            R.string.away_status_escalated,
        )
        AwayDecisionStatus.FAILED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            R.string.away_status_failed,
        )
        AwayDecisionStatus.IGNORED -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            R.string.away_status_ignored,
        )
        AwayDecisionStatus.REJECTED -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            R.string.away_status_rejected,
        )
        AwayDecisionStatus.UNDONE -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            R.string.away_status_undone,
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            R.string.away_status_cancelled,
        )
    }
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = content,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

private fun formatTime(epoch: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epoch))
