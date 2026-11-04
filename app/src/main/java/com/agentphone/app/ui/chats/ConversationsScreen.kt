package com.agentphone.app.ui.chats

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.domain.model.ChatSummary
import com.agentphone.app.ui.components.EmptyState
import com.agentphone.app.ui.components.SkeletonList
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.agentphone.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenChat: (String) -> Unit,
    onProviders: () -> Unit,
    onTools: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val chats by viewModel.chatList.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val providers by viewModel.providerList.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val windowSizeClass = com.agentphone.app.ui.LocalWindowSizeClass.current
    val isExpanded = windowSizeClass?.widthSizeClass ==
        androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Expanded
    val snackbar = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<ChatSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSummary?>(null) }
    var tagTarget by remember { mutableStateOf<ChatSummary?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatsEvent.OpenChat -> onOpenChat(event.chatId)
                is ChatsEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.agentphone.app.ui.components.AgentLogo(size = 30)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                providers.firstOrNull { it.id == settings?.activeProviderId }?.name
                                    ?: stringResource(R.string.app_tagline),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onProviders) {
                        Icon(Icons.Outlined.CloudQueue, contentDescription = stringResource(R.string.providers))
                    }
                    IconButton(onClick = onTools) {
                        Icon(Icons.Outlined.Build, contentDescription = stringResource(R.string.tools_permissions))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.newChat() },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_chat)) },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = if (isExpanded) 1_100.dp else 900.dp)
                    .padding(horizontal = if (isExpanded) 40.dp else 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.search_chats)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
                if (providers.isEmpty() && settings != null) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onProviders),
                        ) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.SmartToy, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.provider_required_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        stringResource(R.string.provider_required_body),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                TextButton(onClick = onProviders) {
                                    Text(stringResource(R.string.add_provider))
                                }
                            }
                        }
                    }
                }
                if (settings == null && chats.isEmpty()) {
                    item { SkeletonList(5) }
                } else if (chats.isEmpty() && query.isNotBlank()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Search,
                            title = stringResource(R.string.no_results),
                            body = "",
                        )
                    }
                } else if (chats.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            title = stringResource(R.string.no_chats_title),
                            body = stringResource(R.string.no_chats_body),
                            actionLabel = stringResource(R.string.new_chat),
                            onAction = { viewModel.newChat() },
                        )
                    }
                } else {
                    val grouped = chats.groupBy {
                        if (it.chat.pinned) ChatGroup.PINNED else groupOf(it.chat.updatedAt)
                    }
                    ChatGroup.entries.forEach { group ->
                        val entries = grouped[group] ?: return@forEach
                        item(key = "header-${group.name}") {
                            Text(
                                text = when (group) {
                                    ChatGroup.PINNED -> stringResource(R.string.pinned)
                                    ChatGroup.TODAY -> stringResource(R.string.today)
                                    ChatGroup.YESTERDAY -> stringResource(R.string.yesterday)
                                    ChatGroup.WEEK -> stringResource(R.string.previous_7_days)
                                    ChatGroup.OLDER -> stringResource(R.string.older)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(entries, key = { it.chat.id }) { summary ->
                            ChatRow(
                                summary = summary,
                                untitled = stringResource(R.string.new_chat),
                                onOpen = { onOpenChat(summary.chat.id) },
                                onPin = {
                                    viewModel.setPinned(summary.chat.id, !summary.chat.pinned)
                                },
                                onRename = { renameTarget = summary },
                                onTag = { tagTarget = summary },
                                onDelete = { deleteTarget = summary },
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        var title by remember(target.chat.id) { mutableStateOf(target.chat.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename_chat)) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.chat_title)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameChat(target.chat.id, title)
                        renameTarget = null
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    tagTarget?.let { target ->
        var tag by remember(target.chat.id) { mutableStateOf(target.chat.tag.orEmpty()) }
        AlertDialog(
            onDismissRequest = { tagTarget = null },
            title = { Text(stringResource(R.string.set_tag)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = tag,
                        onValueChange = { tag = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.tag)) },
                        placeholder = { Text(stringResource(R.string.tag_hint)) },
                    )
                    if (!target.chat.tag.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                viewModel.setTag(target.chat.id, null)
                                tagTarget = null
                            },
                        ) { Text(stringResource(R.string.remove_tag)) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setTag(target.chat.id, tag)
                        tagTarget = null
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { tagTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_chat)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_chat_message,
                        if (target.chat.isUntitled) stringResource(R.string.new_chat) else target.chat.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChat(target)
                        deleteTarget = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ChatRow(
    summary: ChatSummary,
    untitled: String,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onTag: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val title = if (summary.chat.isUntitled) untitled else summary.chat.title
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (summary.chat.pinned) {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = stringResource(R.string.pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    summary.chat.tag?.takeIf { it.isNotBlank() }?.let { tag ->
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = summary.preview.ifBlank { stringResource(R.string.agents_ready) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatTime(summary.chat.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (summary.chat.pinned) R.string.unpin else R.string.pin,
                                ),
                            )
                        },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                        onClick = {
                            menuOpen = false
                            onPin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.set_tag)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null) },
                        onClick = {
                            menuOpen = false
                            onTag()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private enum class ChatGroup {
    PINNED,
    TODAY,
    YESTERDAY,
    WEEK,
    OLDER,
}

private fun groupOf(timestamp: Long): ChatGroup {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    return when {
        date == today -> ChatGroup.TODAY
        date == today.minusDays(1) -> ChatGroup.YESTERDAY
        date.isAfter(today.minusDays(7)) -> ChatGroup.WEEK
        else -> ChatGroup.OLDER
    }
}

@Composable
private fun formatTime(timestamp: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(timestamp).atZone(zone)
    return if (date.toLocalDate() == LocalDate.now(zone)) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(date)
    } else {
        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(date)
    }
}
