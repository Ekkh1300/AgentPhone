package com.agentphone.app.ui.away

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.R
import com.agentphone.app.telegram.AwayResponder
import com.agentphone.app.telegram.AwaySettings
import com.agentphone.app.ui.components.SectionCard
import com.agentphone.app.ui.components.SettingSwitchRow
import java.time.DayOfWeek
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AwayScreen(
    onBack: () -> Unit,
    onRules: () -> Unit,
    onDrafts: () -> Unit,
    onLog: () -> Unit,
    onTelegram: () -> Unit,
    viewModel: AwayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current
    var confirmEnable by rememberSaveable { mutableStateOf(false) }
    var personaDialog by rememberSaveable { mutableStateOf(false) }
    var nicknamesDialog by rememberSaveable { mutableStateOf(false) }
    var disclosureDialog by rememberSaveable { mutableStateOf(false) }
    var simulatorText by rememberSaveable { mutableStateOf("") }
    var simulatorGroup by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.away_title)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    Text(
                        stringResource(R.string.away_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    SettingSwitchRow(
                        title = stringResource(R.string.away_title),
                        subtitle = stringResource(R.string.away_desc),
                        checked = settings.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled) confirmEnable = true else viewModel.setEnabled(false)
                        },
                    )
                    if (settings.enabled && !state.telegramReady) {
                        Text(
                            stringResource(R.string.away_needs_telegram),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!state.providerReady) {
                        Text(
                            stringResource(R.string.away_needs_provider),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    settings.disabledReason?.let { reason ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.away_disabled_reason, reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.away_health_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        val health = when {
                            !settings.enabled -> R.string.away_health_unknown
                            state.serviceAlive -> R.string.away_health_ok
                            else -> R.string.away_health_bad
                        }
                        Text(
                            stringResource(health),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (settings.enabled && !state.serviceAlive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Text(stringResource(R.string.away_delay), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.away_delay_value, settings.delayMinutes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Slider(
                        value = settings.delayMinutes.toFloat(),
                        onValueChange = { viewModel.setDelayMinutes(it.roundToInt()) },
                        valueRange = 5f..360f,
                        steps = 70,
                    )
                    Text(
                        stringResource(R.string.away_delay_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard {
                    SettingSwitchRow(
                        title = stringResource(R.string.away_schedule),
                        subtitle = stringResource(R.string.away_schedule_desc),
                        checked = settings.scheduleEnabled,
                        onCheckedChange = viewModel::setScheduleEnabled,
                    )
                    if (settings.scheduleEnabled) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        TimeSlider(
                            label = stringResource(R.string.away_schedule_start),
                            minute = settings.scheduleStartMinute,
                            onMinute = viewModel::setScheduleStart,
                        )
                        TimeSlider(
                            label = stringResource(R.string.away_schedule_end),
                            minute = settings.scheduleEndMinute,
                            onMinute = viewModel::setScheduleEnd,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.away_days),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DayChip(DayOfWeek.MONDAY, R.string.away_day_mon, settings, viewModel)
                            DayChip(DayOfWeek.TUESDAY, R.string.away_day_tue, settings, viewModel)
                            DayChip(DayOfWeek.WEDNESDAY, R.string.away_day_wed, settings, viewModel)
                            DayChip(DayOfWeek.THURSDAY, R.string.away_day_thu, settings, viewModel)
                            DayChip(DayOfWeek.FRIDAY, R.string.away_day_fri, settings, viewModel)
                            DayChip(DayOfWeek.SATURDAY, R.string.away_day_sat, settings, viewModel)
                            DayChip(DayOfWeek.SUNDAY, R.string.away_day_sun, settings, viewModel)
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text(stringResource(R.string.away_confidence), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.away_confidence_value,
                            (settings.confidenceThreshold * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Slider(
                        value = settings.confidenceThreshold,
                        onValueChange = { viewModel.setConfidence(it) },
                        valueRange = 0.5f..1f,
                        steps = 9,
                    )
                    Text(
                        stringResource(R.string.away_confidence_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard {
                    Text(
                        stringResource(R.string.away_limits_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    ValueSlider(
                        label = stringResource(R.string.away_per_chat_cap),
                        value = stringResource(R.string.away_per_chat_cap_value, settings.perChatDailyCap),
                        current = settings.perChatDailyCap.toFloat(),
                        range = 1f..10f,
                        steps = 8,
                        onChange = { viewModel.setPerChatCap(it.roundToInt()) },
                    )
                    ValueSlider(
                        label = stringResource(R.string.away_global_cap),
                        value = stringResource(R.string.away_global_cap_value, settings.globalDailyCap),
                        current = settings.globalDailyCap.toFloat(),
                        range = 1f..60f,
                        steps = 58,
                        onChange = { viewModel.setGlobalCap(it.roundToInt()) },
                    )
                    ValueSlider(
                        label = stringResource(R.string.away_cooldown),
                        value = stringResource(R.string.away_cooldown_value, settings.cooldownMinutes),
                        current = settings.cooldownMinutes.toFloat(),
                        range = 0f..120f,
                        steps = 23,
                        onChange = { viewModel.setCooldown(it.roundToInt()) },
                    )
                }
            }

            item {
                SectionCard {
                    AwayRow(
                        icon = Icons.AutoMirrored.Outlined.Notes,
                        title = stringResource(R.string.away_persona_title),
                        subtitle = settings.persona.take(90)
                            .ifBlank { stringResource(R.string.away_persona_empty) },
                        onClick = { personaDialog = true },
                    )
                    Text(
                        stringResource(R.string.away_persona_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    AwayRow(
                        icon = Icons.Outlined.Badge,
                        title = stringResource(R.string.away_nicknames),
                        subtitle = settings.nicknames.take(60)
                            .ifBlank { stringResource(R.string.away_nicknames_desc) },
                        onClick = { nicknamesDialog = true },
                    )
                }
            }

            item {
                SectionCard {
                    SettingSwitchRow(
                        title = stringResource(R.string.away_disclosure),
                        subtitle = stringResource(R.string.away_disclosure_desc),
                        checked = settings.disclosureEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setDisclosure(enabled, settings.disclosureText)
                        },
                    )
                    AwayRow(
                        icon = Icons.Outlined.Visibility,
                        title = stringResource(R.string.away_disclosure_custom),
                        subtitle = settings.disclosureText.ifBlank {
                            stringResource(R.string.away_disclosure_hint)
                        },
                        onClick = { disclosureDialog = true },
                    )
                    Text(
                        stringResource(R.string.away_disclosure_group_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard {
                    SettingSwitchRow(
                        title = stringResource(R.string.away_summary),
                        subtitle = stringResource(R.string.away_summary_desc),
                        checked = settings.summaryEnabled,
                        onCheckedChange = viewModel::setSummaryEnabled,
                    )
                }
            }

            item {
                SectionCard {
                    Text(
                        stringResource(R.string.away_chats_section),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.away_chats_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    AwayRow(
                        icon = Icons.Outlined.People,
                        title = stringResource(R.string.away_manage_chats),
                        subtitle = "${state.rules.size}",
                        onClick = onRules,
                    )
                }
            }

            item {
                SectionCard {
                    Text(
                        stringResource(R.string.away_queue_section),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (state.drafts.isEmpty()) {
                            stringResource(R.string.away_queue_desc_none)
                        } else {
                            stringResource(R.string.away_queue_desc, state.drafts.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    AwayRow(
                        icon = Icons.Outlined.Inbox,
                        title = stringResource(R.string.away_open_queue),
                        subtitle = "${state.drafts.size}",
                        onClick = onDrafts,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    AwayRow(
                        icon = Icons.Outlined.History,
                        title = stringResource(R.string.away_open_log),
                        subtitle = "${state.recent.size}",
                        onClick = onLog,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    AwayRow(
                        icon = Icons.Outlined.Send,
                        title = stringResource(R.string.away_open_telegram),
                        subtitle = if (state.telegramReady) {
                            stringResource(R.string.telegram_connected)
                        } else {
                            stringResource(R.string.away_needs_telegram)
                        },
                        onClick = onTelegram,
                    )
                }
            }

            item {
                SectionCard {
                    Text(
                        stringResource(R.string.away_simulator_section),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.away_simulator_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !simulatorGroup,
                            onClick = { simulatorGroup = false },
                            label = { Text(stringResource(R.string.away_simulator_private)) },
                        )
                        FilterChip(
                            selected = simulatorGroup,
                            onClick = { simulatorGroup = true },
                            label = { Text(stringResource(R.string.away_simulator_group)) },
                        )
                    }
                    OutlinedTextField(
                        value = simulatorText,
                        onValueChange = { simulatorText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.away_simulator_message)) },
                        minLines = 3,
                        maxLines = 6,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.simulate(simulatorText, simulatorGroup) },
                        enabled = !state.simulator.running && simulatorText.isNotBlank() && state.providerReady,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (state.simulator.running) {
                                    R.string.away_simulator_running
                                } else {
                                    R.string.away_simulator_run
                                },
                            ),
                        )
                    }
                    if (!state.providerReady) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.away_needs_provider),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    val output = state.simulator.output
                    if (!output.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.away_simulator_result),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        ) {
                            Text(
                                output,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    state.simulator.error?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.away_simulator_error, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.BatteryAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                stringResource(R.string.away_battery_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.away_battery_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val opened = runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                )
                            }.isSuccess
                            if (!opened) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        ),
                                    )
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.away_open_battery)) }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (confirmEnable) {
        var understood by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(stringResource(R.string.away_master_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.away_master_confirm_body))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.i_understand),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEnable = false
                        viewModel.setEnabled(true)
                    },
                    enabled = understood,
                ) { Text(stringResource(R.string.away_master_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (personaDialog) {
        val persianTemplate = stringResource(R.string.away_persona_persian_text)
        var text by remember(settings.persona) { mutableStateOf(settings.persona) }
        AlertDialog(
            onDismissRequest = { personaDialog = false },
            title = { Text(stringResource(R.string.away_persona_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.away_persona_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { text = persianTemplate }) {
                            Text(stringResource(R.string.away_persona_persian))
                        }
                        OutlinedButton(onClick = { text = PERSONA_ENGLISH_TEMPLATE }) {
                            Text(stringResource(R.string.away_persona_english))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 8,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setPersona(text)
                        personaDialog = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { personaDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (nicknamesDialog) {
        var text by remember(settings.nicknames) { mutableStateOf(settings.nicknames) }
        AlertDialog(
            onDismissRequest = { nicknamesDialog = false },
            title = { Text(stringResource(R.string.away_nicknames)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.away_nicknames_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setNicknames(text)
                        nicknamesDialog = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { nicknamesDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (disclosureDialog) {
        var text by remember(settings.disclosureText) { mutableStateOf(settings.disclosureText) }
        AlertDialog(
            onDismissRequest = { disclosureDialog = false },
            title = { Text(stringResource(R.string.away_disclosure_custom)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.away_disclosure_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setDisclosure(settings.disclosureEnabled, text)
                        disclosureDialog = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { disclosureDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

}

private val PERSONA_ENGLISH_TEMPLATE = AwayResponder.DEFAULT_PERSONA

@Composable
private fun TimeSlider(label: String, minute: Int, onMinute: (Int) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatMinute(minute),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = minute.coerceIn(0, 24 * 60 - 30).toFloat(),
            onValueChange = { onMinute((it / 30).roundToInt() * 30) },
            valueRange = 0f..(24 * 60 - 30).toFloat(),
            steps = 46,
        )
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = current,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun DayChip(
    day: DayOfWeek,
    label: Int,
    settings: AwaySettings,
    viewModel: AwayViewModel,
) {
    FilterChip(
        selected = settings.dayEnabled(day),
        onClick = { viewModel.toggleDay(day) },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun AwayRow(
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

private fun formatMinute(minute: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)
