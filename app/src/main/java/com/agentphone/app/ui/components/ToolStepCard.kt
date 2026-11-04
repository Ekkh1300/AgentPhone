package com.agentphone.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentphone.app.R
import com.agentphone.app.core.NumberFormatting
import com.agentphone.app.domain.model.ChatMessage
import com.agentphone.app.domain.model.MessageStatus

@Composable
fun ToolStepCard(
    message: ChatMessage,
    persianDigits: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable(message.id) {
        mutableStateOf(message.status == MessageStatus.STREAMING || message.status == MessageStatus.ERROR)
    }
    val toolName = message.toolName ?: "tool"
    val statusLabel = when (message.status) {
        MessageStatus.STREAMING -> stringResource(R.string.status_running)
        MessageStatus.COMPLETE -> stringResource(R.string.done)
        MessageStatus.ERROR -> stringResource(R.string.status_failed)
        MessageStatus.CANCELLED -> stringResource(R.string.status_cancelled)
    }
    val statusColor = when (message.status) {
        MessageStatus.STREAMING -> MaterialTheme.colorScheme.primary
        MessageStatus.COMPLETE -> MaterialTheme.colorScheme.primary
        MessageStatus.ERROR -> MaterialTheme.colorScheme.error
        MessageStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (message.status == MessageStatus.ERROR) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        toolIcon(toolName),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        toolName.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(statusColor),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                        message.durationMs?.let {
                            Text(
                                " · " + NumberFormatting.formatDuration(it, persianDigits),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) {
                            stringResource(R.string.hide_details)
                        } else {
                            stringResource(R.string.show_details)
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (message.toolWarning != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            message.toolWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            if (expanded) {
                if (!message.toolArgs.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.arguments),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            message.toolArgs.take(4_000),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.result),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(message.content)) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.copy_result),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        message.content.ifBlank { "(no output)" }.take(12_000),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        ),
                        color = if (message.status == MessageStatus.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(10.dp),
                    )
                }
                if (message.artifactUri != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(
                                                Uri.parse(message.artifactUri),
                                                message.artifactMime ?: "*/*",
                                            )
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                    )
                                }.onFailure {
                                    Toast.makeText(
                                        context,
                                        it.message ?: context.getString(R.string.no_app_can_open_file),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.open_file))
                        }
                        TextButton(
                            onClick = {
                                val uri = Uri.parse(message.artifactUri)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = message.artifactMime ?: "*/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            share,
                                            message.artifactName ?: context.getString(R.string.share_file),
                                        ),
                                    )
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Share, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.share))
                        }
                    }
                }
            }
        }
    }
}

private fun toolIcon(name: String): ImageVector = when {
    name.startsWith("files_") -> Icons.Outlined.Folder
    name.startsWith("web_") -> Icons.Outlined.CloudQueue
    name.startsWith("calendar_") -> Icons.Outlined.CalendarMonth
    name.startsWith("contacts_") -> Icons.Outlined.Contacts
    name.startsWith("screen_") -> Icons.Outlined.AccessibilityNew
    name.startsWith("mcp_") -> Icons.Outlined.Terminal
    name.startsWith("mcp") -> Icons.Outlined.Terminal
    name == "location_current" -> Icons.Outlined.LocationOn
    name == "camera_capture" -> Icons.Outlined.CameraAlt
    name == "flashlight" -> Icons.Outlined.FlashlightOn
    name == "volume_control" -> Icons.AutoMirrored.Outlined.VolumeUp
    name == "brightness_set" -> Icons.Outlined.Brightness6
    name == "notification_post" || name == "reminder_schedule" -> Icons.Outlined.Notifications
    name == "alarm_set" || name == "timer_set" -> Icons.Outlined.Alarm
    name == "device_info" -> Icons.Outlined.PhoneAndroid
    name.startsWith("memory_") -> Icons.Outlined.Memory
    name.startsWith("clipboard_") -> Icons.Outlined.ContentCopy
    else -> Icons.Outlined.Description
}
