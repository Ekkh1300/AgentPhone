package com.agentphone.app.ui.away

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.R
import com.agentphone.app.data.local.entity.AwayChatMode
import com.agentphone.app.data.local.entity.AwayChatRuleEntity
import com.agentphone.app.data.local.entity.AwayChatType
import com.agentphone.app.telegram.ChannelChat
import com.agentphone.app.ui.components.EmptyState
import com.agentphone.app.ui.components.Pill
import com.agentphone.app.ui.components.SectionCard
import com.agentphone.app.ui.components.SettingSwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AwayRulesScreen(
    onBack: () -> Unit,
    viewModel: AwayRulesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerVisible by rememberSaveable { mutableStateOf(false) }
    var editingRuleId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.away_rules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            pickerVisible = true
                            viewModel.loadChats()
                        },
                    ) { Text(stringResource(R.string.away_rules_add)) }
                },
            )
        },
    ) { padding ->
        if (state.rules.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                EmptyState(
                    icon = Icons.Outlined.People,
                    title = stringResource(R.string.away_rules_empty),
                    body = stringResource(R.string.away_rules_empty_body),
                    modifier = Modifier.weight(1f),
                    actionLabel = stringResource(R.string.away_rules_add),
                    onAction = {
                        pickerVisible = true
                        viewModel.loadChats()
                    },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.rules, key = { it.chatId }) { rule ->
                    RuleCard(
                        rule = rule,
                        onMode = { viewModel.setMode(rule, it) },
                        onSendToAi = { viewModel.setSendToAi(rule, it) },
                        onDisclosure = { viewModel.setDisclosure(rule, it) },
                        onInstructions = { editingRuleId = rule.chatId },
                        onRemove = { viewModel.remove(rule.chatId) },
                    )
                }
                item {
                    Text(
                        stringResource(R.string.away_rules_empty_body),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    if (pickerVisible) {
        val available = state.chats.filter { chat -> state.rules.none { it.chatId == chat.id } }
        AlertDialog(
            onDismissRequest = { pickerVisible = false },
            title = { Text(stringResource(R.string.away_rules_add)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (!state.telegramReady) {
                        Text(
                            stringResource(R.string.away_rules_not_connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    state.error?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    when {
                        state.loadingChats -> {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(vertical = 20.dp)
                                    .align(Alignment.CenterHorizontally),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                stringResource(R.string.away_rules_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        available.isEmpty() && state.telegramReady && !state.loadingChats -> {
                            Text(
                                stringResource(R.string.away_rules_no_chats),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> {
                            available.forEach { chat ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.addChat(chat)
                                            pickerVisible = false
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(chat.title, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            stringResource(typeLabel(chat.kind)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (!chat.canSend) {
                                        Pill(stringResource(R.string.away_rule_draft))
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val editing = state.rules.firstOrNull { it.chatId == editingRuleId }
    if (editing != null) {
        var text by remember(editing.chatId, editing.instructions) {
            mutableStateOf(editing.instructions.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { editingRuleId = null },
            title = { Text(stringResource(R.string.away_rule_instructions)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setInstructions(editing, text)
                        editingRuleId = null
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingRuleId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: AwayChatRuleEntity,
    onMode: (String) -> Unit,
    onSendToAi: (Boolean) -> Unit,
    onDisclosure: (Boolean?) -> Unit,
    onInstructions: () -> Unit,
    onRemove: () -> Unit,
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
                        rule.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(typeLabel(rule.chatType)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.away_rule_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.away_rule_mode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(R.string.away_rule_default, AwayChatMode.DEFAULT, rule.mode, onMode)
                ModeChip(R.string.away_rule_auto, AwayChatMode.AUTO, rule.mode, onMode)
                ModeChip(R.string.away_rule_draft, AwayChatMode.DRAFT, rule.mode, onMode)
                ModeChip(R.string.away_rule_never, AwayChatMode.NEVER, rule.mode, onMode)
            }
            if (rule.chatType == AwayChatType.GROUP) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.away_rule_group_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingSwitchRow(
                title = stringResource(R.string.away_rule_send_to_ai),
                subtitle = stringResource(R.string.away_rule_send_to_ai_desc),
                checked = rule.sendToAi,
                onCheckedChange = onSendToAi,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.away_rule_disclosure),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = rule.disclosure == null,
                    onClick = { onDisclosure(null) },
                    label = { Text(stringResource(R.string.away_rule_disclosure_default)) },
                )
                FilterChip(
                    selected = rule.disclosure == true,
                    onClick = { onDisclosure(true) },
                    label = { Text(stringResource(R.string.away_rule_disclosure_on)) },
                )
                FilterChip(
                    selected = rule.disclosure == false,
                    onClick = { onDisclosure(false) },
                    label = { Text(stringResource(R.string.away_rule_disclosure_off)) },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${stringResource(R.string.away_rule_instructions)}: " +
                    (rule.instructions?.take(60) ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onInstructions)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ModeChip(
    label: Int,
    mode: String,
    current: String,
    onMode: (String) -> Unit,
) {
    FilterChip(
        selected = current == mode,
        onClick = { onMode(mode) },
        label = { Text(stringResource(label)) },
    )
}

private fun typeLabel(kind: String): Int = when (kind) {
    AwayChatType.GROUP -> R.string.away_type_group
    AwayChatType.CHANNEL -> R.string.away_type_channel
    AwayChatType.BOT -> R.string.away_type_bot
    else -> R.string.away_type_private
}
