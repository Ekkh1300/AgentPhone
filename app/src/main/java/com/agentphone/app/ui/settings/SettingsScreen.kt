package com.agentphone.app.ui.settings

import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.BuildConfig
import kotlinx.coroutines.launch
import com.agentphone.app.R
import com.agentphone.app.core.AppLanguage
import com.agentphone.app.core.NumberFormatting
import com.agentphone.app.domain.model.ThemeMode
import com.agentphone.app.ui.components.SectionCard
import com.agentphone.app.ui.components.SettingSwitchRow
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProviders: () -> Unit,
    onTools: () -> Unit,
    onAudit: () -> Unit,
    onAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var memoryDialog by remember { mutableStateOf(false) }
    var promptDialog by remember { mutableStateOf(false) }
    var webKeyDialog by remember { mutableStateOf(false) }
    var mcpKeyDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val data = viewModel.exportBackup()
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(data) }
                        ?: error(context.getString(R.string.backup_write_failed))
                }.onSuccess {
                    Toast.makeText(context, R.string.backup_exported, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_failed, it.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        it.readText()
                    } ?: error(context.getString(R.string.backup_read_failed))
                    require(raw.length < 30_000_000) { context.getString(R.string.backup_too_large) }
                    viewModel.importBackup(raw)
                }.onSuccess { count ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_imported, count),
                        Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_failed, it.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionTitle(stringResource(R.string.ai_section))
                SectionCard {
                    SettingsRow(
                        icon = Icons.Outlined.CloudQueue,
                        title = stringResource(R.string.providers),
                        subtitle = state.providers.firstOrNull {
                            it.id == settings.activeProviderId
                        }?.name ?: stringResource(R.string.add_provider),
                        onClick = onProviders,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SettingsRow(
                        icon = Icons.Outlined.Build,
                        title = stringResource(R.string.tools_permissions),
                        subtitle = null,
                        onClick = onTools,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SettingsRow(
                        icon = Icons.Outlined.History,
                        title = stringResource(R.string.activity_log),
                        subtitle = null,
                        onClick = onAudit,
                    )
                }
            }

            item {
                SectionTitle(stringResource(R.string.agent_section))
                SectionCard {
                    SettingSwitchRow(
                        title = stringResource(R.string.auto_title),
                        subtitle = stringResource(R.string.auto_title_desc),
                        checked = settings.autoTitle,
                        onCheckedChange = viewModel::setAutoTitle,
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.stream_responses),
                        subtitle = stringResource(R.string.stream_responses_desc),
                        checked = settings.streamResponses,
                        onCheckedChange = viewModel::setStreaming,
                    )
                    Text(
                        stringResource(R.string.max_agent_steps),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "${settings.maxAgentSteps}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Slider(
                        value = settings.maxAgentSteps.toFloat(),
                        onValueChange = { viewModel.setMaxSteps(it.roundToInt()) },
                        valueRange = 1f..20f,
                        steps = 18,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Memory,
                        title = stringResource(R.string.memory),
                        subtitle = if (settings.memoryNotes.isBlank()) {
                            stringResource(R.string.memory_empty)
                        } else {
                            settings.memoryNotes.take(80)
                        },
                        onClick = { memoryDialog = true },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SettingSwitchRow(
                        title = stringResource(R.string.allow_private_web),
                        subtitle = stringResource(R.string.allow_private_web_desc),
                        checked = settings.allowPrivateWeb,
                        onCheckedChange = viewModel::setAllowPrivateWeb,
                    )
                }
            }

            item {
                SectionTitle(stringResource(R.string.generation_section))
                SectionCard {
                    SettingsRow(
                        icon = Icons.Outlined.Tune,
                        title = stringResource(R.string.system_prompt),
                        subtitle = settings.systemPrompt.take(100),
                        onClick = { promptDialog = true },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        "${stringResource(R.string.temperature)}: ${String.format(Locale.getDefault(), "%.1f", settings.temperature)}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Slider(
                        value = settings.temperature,
                        onValueChange = viewModel::setTemperature,
                        valueRange = 0f..2f,
                        steps = 19,
                    )
                    Text(
                        "${stringResource(R.string.max_output_tokens)}: ${settings.maxOutputTokens}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Slider(
                        value = settings.maxOutputTokens.toFloat(),
                        onValueChange = { viewModel.setMaxOutput(it.roundToInt()) },
                        valueRange = 256f..16_384f,
                        steps = 62,
                    )
                    Text(
                        "${stringResource(R.string.context_window_default)}: ${settings.contextWindowTokens}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Slider(
                        value = settings.contextWindowTokens.toFloat(),
                        onValueChange = { viewModel.setContextWindow(it.roundToInt()) },
                        valueRange = 4_096f..262_144f,
                        steps = 62,
                    )
                }
            }

            item {
                SectionTitle(stringResource(R.string.appearance))
                SectionCard {
                    Text(
                        stringResource(R.string.theme),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setTheme(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                            ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                            ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                        },
                                    )
                                },
                            )
                        }
                    }
                    SettingSwitchRow(
                        title = stringResource(R.string.dynamic_color),
                        subtitle = stringResource(R.string.dynamic_color_desc),
                        checked = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onCheckedChange = viewModel::setDynamicColor,
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.amoled),
                        subtitle = stringResource(R.string.amoled_desc),
                        checked = settings.amoled,
                        onCheckedChange = viewModel::setAmoled,
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.haptics),
                        checked = settings.haptics,
                        onCheckedChange = viewModel::setHaptics,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Language, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.language),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "system" to stringResource(R.string.language_system),
                            "en" to stringResource(R.string.language_english),
                            "fa" to stringResource(R.string.language_persian),
                        ).forEach { (tag, label) ->
                            FilterChip(
                                selected = settings.language == tag,
                                onClick = {
                                    AppLanguage.set(context, tag)
                                    viewModel.setLanguage(tag)
                                    activity?.recreate()
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    SettingSwitchRow(
                        title = stringResource(R.string.persian_digits),
                        subtitle = stringResource(R.string.persian_digits_desc),
                        checked = settings.persianDigits,
                        onCheckedChange = viewModel::setPersianDigits,
                    )
                }
            }

            item {
                SectionTitle(stringResource(R.string.web_search))
                SectionCard {
                    Text(
                        stringResource(R.string.web_search_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.webSearchEndpoint,
                        onValueChange = viewModel::setWebSearchEndpoint,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.web_search_endpoint)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { webKeyDialog = true }) {
                        Text(
                            if (state.hasWebSearchKey) {
                                "${stringResource(R.string.web_search_key)} · ${stringResource(R.string.web_key_saved)}"
                            } else {
                                stringResource(R.string.web_search_key)
                            },
                        )
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.mcp))
                SectionCard {
                    Text(
                        stringResource(R.string.mcp_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.mcp_enabled),
                        checked = settings.mcpEnabled,
                        onCheckedChange = viewModel::setMcpEnabled,
                    )
                    OutlinedTextField(
                        value = settings.mcpServerUrl,
                        onValueChange = viewModel::setMcpUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.mcp_url)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { mcpKeyDialog = true }) {
                        Text(
                            if (state.hasMcpKey) {
                                "${stringResource(R.string.mcp_key)} · ${stringResource(R.string.web_key_saved)}"
                            } else {
                                stringResource(R.string.mcp_key)
                            },
                        )
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.privacy_section))
                SectionCard {
                    Text(
                        stringResource(R.string.total_usage),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            R.string.total_usage_value,
                            NumberFormatting.formatInt(settings.totalPromptTokens, settings.persianDigits),
                            NumberFormatting.formatInt(settings.totalCompletionTokens, settings.persianDigits),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.privacy_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { exportLauncher.launch("agentphone-backup.json") }) {
                        Text(stringResource(R.string.export_chats))
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Text(stringResource(R.string.import_chats))
                    }
                    Text(
                        stringResource(R.string.backup_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { clearDialog = true }) {
                        Icon(Icons.Outlined.WarningAmber, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.clear_all_data))
                    }
                    Text(
                        stringResource(R.string.clear_all_data_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard {
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.about),
                        subtitle = stringResource(R.string.version, BuildConfig.VERSION_NAME),
                        onClick = onAbout,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.built_with),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }

    if (memoryDialog) {
        var text by remember { mutableStateOf(settings.memoryNotes) }
        AlertDialog(
            onDismissRequest = { memoryDialog = false },
            title = { Text(stringResource(R.string.memory)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    label = { Text(stringResource(R.string.memory_desc)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setMemory(text)
                        memoryDialog = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { memoryDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (promptDialog) {
        var text by remember { mutableStateOf(settings.systemPrompt) }
        AlertDialog(
            onDismissRequest = { promptDialog = false },
            title = { Text(stringResource(R.string.system_prompt)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setSystemPrompt(text)
                        promptDialog = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { promptDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (webKeyDialog) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { webKeyDialog = false },
            title = { Text(stringResource(R.string.web_search_key)) },
            text = {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveWebSearchKey(key)
                        webKeyDialog = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { webKeyDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (mcpKeyDialog) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { mcpKeyDialog = false },
            title = { Text(stringResource(R.string.mcp_key)) },
            text = {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveMcpKey(key)
                        mcpKeyDialog = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { mcpKeyDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text(stringResource(R.string.clear_all_confirm)) },
            text = { Text(stringResource(R.string.clear_all_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllData()
                        clearDialog = false
                    },
                ) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { clearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
