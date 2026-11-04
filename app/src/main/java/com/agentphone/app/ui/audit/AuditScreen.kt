package com.agentphone.app.ui.audit

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.R
import com.agentphone.app.core.NumberFormatting
import com.agentphone.app.data.local.entity.ToolCallLogEntity
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.ui.components.EmptyState
import com.agentphone.app.ui.components.Pill
import com.agentphone.app.ui.components.RiskChip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(
    onBack: () -> Unit,
    viewModel: AuditViewModel = hiltViewModel(),
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_log)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { confirmClear = true }, enabled = logs.isNotEmpty()) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.clear_logs))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (logs.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.audit_empty_title),
                    body = stringResource(R.string.audit_empty_body),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.audit_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(logs, key = { it.id }) { log ->
                        AuditCard(log)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_logs_confirm)) },
            text = { Text(stringResource(R.string.clear_logs_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clear()
                        confirmClear = false
                    },
                ) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AuditCard(log: ToolCallLogEntity) {
    var expanded by rememberSaveable(log.id) { mutableStateOf(false) }
    val risk = runCatching { RiskLevel.valueOf(log.risk) }.getOrDefault(RiskLevel.READ)
    val statusColor = when (log.status) {
        "SUCCESS" -> MaterialTheme.colorScheme.primary
        "DENIED" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        log.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(log.startedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RiskChip(risk)
                Spacer(Modifier.width(6.dp))
                Pill(
                    text = when (log.status) {
                        "SUCCESS" -> stringResource(R.string.done)
                        "DENIED" -> stringResource(R.string.denied)
                        "CANCELLED" -> stringResource(R.string.status_cancelled)
                        else -> stringResource(R.string.status_failed)
                    },
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = statusColor,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(R.string.details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    NumberFormatting.formatDuration(log.durationMs, false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.approved_by, log.approval),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (log.injectionWarning) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = stringResource(R.string.injection_flagged),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.arguments),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CodeSurface(log.argumentsJson)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.result),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CodeSurface(log.resultPreview)
            }
        }
    }
}

@Composable
private fun CodeSurface(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
    ) {
        Text(
            text.ifBlank { "(empty)" }.take(6_000),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            ),
            modifier = Modifier.padding(8.dp),
        )
    }
}
