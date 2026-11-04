package com.agentphone.app.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.R
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.ui.components.Pill
import com.agentphone.app.ui.components.RiskChip
import com.agentphone.app.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showAdbDialog by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::connectSaf) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openSpecial(toolName: String) {
        val intent = when (toolName) {
            "screen_snapshot", "screen_action", "airplane_mode", "quick_toggle" ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "brightness_set", "display_control" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            "dnd_set" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "notifications_list", "notification_action" ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "apps_usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "system_settings" -> {
                showAdbDialog = true
                return
            }
            else -> null
        }
        if (intent != null) {
            runCatching { context.startActivity(intent) }
        } else {
            onSettings()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_permissions)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.retry))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.tools_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.tools_enabled_count,
                        state.tools.count { it.enabled },
                        state.tools.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                stringResource(R.string.dangerous_tools_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.advanced_tools_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.shared_folder),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (state.safConnected) {
                                    stringResource(R.string.shared_folder_connected)
                                } else {
                                    stringResource(R.string.shared_folder_not_connected)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.safConnected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (state.safConnected) {
                            OutlinedButton(onClick = viewModel::disconnectSaf) {
                                Text(stringResource(R.string.disconnect))
                            }
                        } else {
                            Button(onClick = { folderPicker.launch(null) }) {
                                Text(stringResource(R.string.connect_folder))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.shared_folder_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.advanced_access),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.advanced_access_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        AccessRow(
                            icon = Icons.Outlined.FolderOpen,
                            title = stringResource(R.string.all_files_access),
                            granted = state.access.allFiles,
                            onGrant = { viewModel.openSpecialAccess("all_files") },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        AccessRow(
                            icon = Icons.Outlined.Notifications,
                            title = stringResource(R.string.notification_access),
                            granted = state.access.notifications,
                            onGrant = { viewModel.openSpecialAccess("notifications") },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        AccessRow(
                            icon = Icons.Outlined.QueryStats,
                            title = stringResource(R.string.usage_access),
                            granted = state.access.usage,
                            onGrant = { viewModel.openSpecialAccess("usage") },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        AccessRow(
                            icon = Icons.Outlined.Accessibility,
                            title = stringResource(R.string.screen_control_access),
                            granted = state.access.accessibility,
                            onGrant = { viewModel.openSpecialAccess("accessibility") },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        AccessRow(
                            icon = Icons.Outlined.Tune,
                            title = stringResource(R.string.write_settings_access),
                            granted = state.access.writeSettings,
                            onGrant = { viewModel.openSpecialAccess("write_settings") },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        AccessRow(
                            icon = Icons.Outlined.DoNotDisturbOn,
                            title = stringResource(R.string.dnd_access),
                            granted = state.access.dnd,
                            onGrant = { viewModel.openSpecialAccess("dnd") },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        AccessRow(
                            icon = Icons.Outlined.InstallMobile,
                            title = stringResource(R.string.install_apps_access),
                            granted = state.access.installApps,
                            onGrant = { viewModel.openSpecialAccess("install_apps") },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        AccessRow(
                            icon = Icons.Outlined.Build,
                            title = stringResource(R.string.secure_settings_access),
                            granted = state.access.secureSettings,
                            onGrant = { showAdbDialog = true },
                        )
                    }
                }
            }

            ToolCategory.entries.forEach { category ->
                val tools = state.tools.filter { it.tool.category == category }
                if (tools.isEmpty()) return@forEach
                item(key = "cat-${category.name}") {
                    Text(
                        text = categoryLabel(category),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                    )
                }
                items(tools.size, key = { tools[it].tool.name }) { index ->
                    val row = tools[index]
                    ToolCard(
                        row = row,
                        onToggle = { viewModel.toggleTool(row.tool.name, it) },
                        onAlwaysAllow = {
                            viewModel.toggleAlwaysAllow(row.tool.name, it)
                        },
                        onGrant = {
                            when (row.access) {
                                ToolAccess.NEEDS_PERMISSION -> viewModel.requestRuntimePermission(row.tool.name)
                                ToolAccess.NEEDS_ACCESSIBILITY, ToolAccess.NEEDS_SETTINGS,
                                ToolAccess.NOT_CONFIGURED -> openSpecial(row.tool.name)
                                ToolAccess.READY -> Unit
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAdbDialog) {
        val command = viewModel.adbGrantCommand()
        AlertDialog(
            onDismissRequest = { showAdbDialog = false },
            title = { Text(stringResource(R.string.secure_settings_access)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.secure_settings_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    SelectionContainer {
                        Text(
                            command,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("adb", command))
                        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                    },
                ) { Text(stringResource(R.string.copy)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdbDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun AccessRow(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (granted) R.string.access_granted else R.string.access_not_granted),
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (!granted) {
            TextButton(onClick = onGrant) {
                Text(stringResource(R.string.grant))
            }
        }
    }
}

@Composable
private fun ToolCard(
    row: ToolRowState,
    onToggle: (Boolean) -> Unit,
    onAlwaysAllow: (Boolean) -> Unit,
    onGrant: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Build,
                    contentDescription = null,
                    tint = if (row.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.tool.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RiskChip(row.tool.riskLevel)
                        val label = when (row.access) {
                            ToolAccess.READY -> stringResource(R.string.tool_ready)
                            ToolAccess.NEEDS_PERMISSION -> stringResource(R.string.tool_needs_permission)
                            ToolAccess.NEEDS_ACCESSIBILITY -> stringResource(R.string.tool_needs_accessibility)
                            ToolAccess.NEEDS_SETTINGS -> stringResource(R.string.tool_needs_settings)
                            ToolAccess.NOT_CONFIGURED -> stringResource(R.string.tool_not_configured)
                        }
                        Pill(
                            text = label,
                            container = if (row.access == ToolAccess.READY) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            content = if (row.access == ToolAccess.READY) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Switch(checked = row.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                row.tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.access != ToolAccess.READY) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onGrant) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (row.access) {
                            ToolAccess.NEEDS_PERMISSION -> stringResource(R.string.allow_permission)
                            ToolAccess.NEEDS_ACCESSIBILITY -> stringResource(R.string.tool_needs_accessibility)
                            ToolAccess.NEEDS_SETTINGS -> stringResource(R.string.tool_needs_settings)
                            ToolAccess.NOT_CONFIGURED -> stringResource(R.string.settings)
                            ToolAccess.READY -> stringResource(R.string.tool_ready)
                        },
                    )
                }
            }
            if (row.tool.riskLevel == RiskLevel.WRITE && row.enabled) {
                TextButton(onClick = { onAlwaysAllow(!row.alwaysAllow) }) {
                    Text(
                        if (row.alwaysAllow) {
                            stringResource(R.string.always_allow) + " ✓"
                        } else {
                            stringResource(R.string.ask_each_time)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun categoryLabel(category: ToolCategory): String = when (category) {
    ToolCategory.FILES -> stringResource(R.string.category_files)
    ToolCategory.DEVICE -> stringResource(R.string.category_device)
    ToolCategory.COMMUNICATION -> stringResource(R.string.category_communication)
    ToolCategory.MEDIA -> stringResource(R.string.category_media)
    ToolCategory.WEB -> stringResource(R.string.category_web)
    ToolCategory.SYSTEM -> stringResource(R.string.category_system)
    ToolCategory.ADVANCED -> stringResource(R.string.category_advanced)
}
