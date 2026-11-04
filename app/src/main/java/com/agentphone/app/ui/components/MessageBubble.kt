package com.agentphone.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.agentphone.app.R
import com.agentphone.app.core.NumberFormatting
import com.agentphone.app.domain.model.ChatMessage
import com.agentphone.app.domain.model.MessageRole
import com.agentphone.app.domain.model.MessageStatus
import java.io.File

@Composable
fun MessageBubble(
    message: ChatMessage,
    persianDigits: Boolean,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    showActions: Boolean = true,
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onSpeak: () -> Unit = {},
    onEdit: () -> Unit = {},
) {
    val isUser = message.role == MessageRole.USER
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser) {
            Row(verticalAlignment = Alignment.Top) {
                AgentLogo(Modifier.padding(top = 2.dp), size = 28)
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        AssistantContent(message, persianDigits, isStreaming)
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 6.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 20.dp,
                ),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.imageUris.isNotEmpty()) {
                        AttachmentGrid(message.imageUris)
                    }
                    if (message.content.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                message.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
        if (showActions && !isStreaming) {
            Row(
                Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isUser) {
                    MessageAction(Icons.Outlined.ContentCopy, stringResource(R.string.copy), onCopy)
                    MessageAction(Icons.Outlined.Share, stringResource(R.string.share), onShare)
                    MessageAction(Icons.Outlined.Refresh, stringResource(R.string.regenerate), onRegenerate)
                    MessageAction(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.read_aloud), onSpeak)
                    MessageAction(Icons.Outlined.DeleteOutline, stringResource(R.string.delete), onDelete)
                } else {
                    MessageAction(Icons.Outlined.ContentCopy, stringResource(R.string.copy), onCopy)
                    MessageAction(Icons.Outlined.Edit, stringResource(R.string.edit_and_resend), onEdit)
                    MessageAction(Icons.Outlined.DeleteOutline, stringResource(R.string.delete), onDelete)
                }
                if (message.inputTokens != null || message.outputTokens != null) {
                    Spacer(Modifier.width(6.dp))
                    Pill(
                        text = buildString {
                            if (message.inputTokens != null) {
                                append(
                                    stringResource(
                                        R.string.tokens_in_short,
                                        NumberFormatting.formatTokens(message.inputTokens, persianDigits),
                                    ),
                                )
                            }
                            if (message.outputTokens != null) {
                                if (isNotEmpty()) append(" · ")
                                append(
                                    stringResource(
                                        R.string.tokens_out_short,
                                        NumberFormatting.formatTokens(message.outputTokens, persianDigits),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
        if (message.status == MessageStatus.ERROR && message.error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Text(
                    message.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantContent(
    message: ChatMessage,
    persianDigits: Boolean,
    isStreaming: Boolean,
) {
    var reasoningExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    if (!message.reasoning.isNullOrBlank()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.thinking),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { reasoningExpanded = !reasoningExpanded },
                    modifier = Modifier.size(26.dp),
                ) {
                    Icon(
                        if (reasoningExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (reasoningExpanded) {
                            stringResource(R.string.hide_reasoning)
                        } else {
                            stringResource(R.string.show_reasoning)
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (reasoningExpanded) {
                Text(
                    message.reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(0.dp))
    }
    if (message.content.isNotBlank()) {
        SelectionContainer {
            MarkdownText(
                markdown = message.content,
                isStreaming = isStreaming,
                modifier = Modifier.padding(top = if (message.reasoning.isNullOrBlank()) 0.dp else 8.dp),
            )
        }
    } else if (isStreaming) {
        LoadingDots(Modifier.padding(vertical = 6.dp))
    }
    if (message.imageUris.isNotEmpty()) {
        AttachmentGrid(message.imageUris)
    }
}

@Composable
private fun AttachmentGrid(uris: List<String>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        uris.take(3).forEach { uri ->
            val model = if (uri.startsWith("content://") || uri.startsWith("file://")) {
                uri
            } else {
                File(uri)
            }
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.attached_image),
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun MessageAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.size(15.dp),
        )
    }
}
