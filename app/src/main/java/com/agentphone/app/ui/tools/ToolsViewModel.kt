package com.agentphone.app.ui.tools

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.R
import com.agentphone.app.agent.ToolRegistry
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.model.AppSettings
import com.agentphone.app.platform.AgentNotificationListener
import com.agentphone.app.platform.AllFiles
import com.agentphone.app.platform.PermissionCoordinator
import com.agentphone.app.platform.SafFiles
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ToolAccess {
    READY,
    NEEDS_PERMISSION,
    NEEDS_ACCESSIBILITY,
    NEEDS_SETTINGS,
    NOT_CONFIGURED,
}

data class ToolRowState(
    val tool: AgentTool,
    val enabled: Boolean,
    val access: ToolAccess,
    val alwaysAllow: Boolean,
)

data class SpecialAccess(
    val allFiles: Boolean = false,
    val notifications: Boolean = false,
    val usage: Boolean = false,
    val accessibility: Boolean = false,
    val writeSettings: Boolean = false,
    val secureSettings: Boolean = false,
    val dnd: Boolean = false,
    val installApps: Boolean = false,
)

data class ToolsUiState(
    val loading: Boolean = true,
    val tools: List<ToolRowState> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val safConnected: Boolean = false,
    val access: SpecialAccess = SpecialAccess(),
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ToolRegistry,
    private val settingsRepository: SettingsRepository,
    private val permissions: PermissionCoordinator,
    private val saf: SafFiles,
    private val allFiles: AllFiles,
) : ViewModel() {

    private val accessMap = MutableStateFlow<Map<String, ToolAccess>>(emptyMap())

    val state: StateFlow<ToolsUiState> = combine(
        settingsRepository.settings,
        accessMap,
    ) { settings, access ->
        ToolsUiState(
            loading = false,
            tools = registry.all.map { tool ->
                ToolRowState(
                    tool = tool,
                    enabled = tool.name in settings.enabledTools,
                    access = access[tool.name] ?: ToolAccess.READY,
                    alwaysAllow = tool.name in settings.alwaysAllowTools,
                )
            },
            settings = settings,
            safConnected = !settings.safTreeUri.isNullOrBlank(),
            access = SpecialAccess(
                allFiles = allFiles.hasAccess(),
                notifications = AgentNotificationListener.isEnabled(context),
                usage = usageAccess(),
                accessibility = accessibilityEnabled(),
                writeSettings = Settings.System.canWrite(context),
                secureSettings = granted(Manifest.permission.WRITE_SECURE_SETTINGS),
                dnd = dndAccess(),
                installApps = context.packageManager.canRequestPackageInstalls(),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ToolsUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val settings = settingsRepository.current()
            accessMap.value = registry.all.associate { it.name to accessFor(it, settings) }
        }
    }

    fun toggleTool(name: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(
                    enabledTools = if (enabled) it.enabledTools + name else it.enabledTools - name,
                )
            }
        }
    }

    fun toggleAlwaysAllow(name: String, allow: Boolean) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(
                    alwaysAllowTools = if (allow) it.alwaysAllowTools + name
                    else it.alwaysAllowTools - name,
                )
            }
        }
    }

    fun requestRuntimePermission(toolName: String) {
        viewModelScope.launch {
            when (toolName) {
                "camera_capture", "flashlight" -> permissions.ensureCamera()
                "location_current" -> permissions.ensureLocation(fine = true)
                "calendar_list", "calendar_create" -> permissions.ensure(
                    listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                    context.getString(R.string.permission_calendar_title),
                    context.getString(R.string.permission_calendar_rationale),
                )
                "contacts_search" -> permissions.ensure(
                    listOf(Manifest.permission.READ_CONTACTS),
                    context.getString(R.string.permission_contacts_title),
                    context.getString(R.string.permission_contacts_rationale),
                )
                "contact_create" -> permissions.ensure(
                    listOf(Manifest.permission.WRITE_CONTACTS),
                    context.getString(R.string.permission_contacts_write_title),
                    context.getString(R.string.permission_contacts_write_rationale),
                )
                "sms_list" -> permissions.ensure(
                    listOf(Manifest.permission.READ_SMS),
                    context.getString(R.string.permission_messages_title),
                    context.getString(R.string.permission_messages_rationale),
                )
                "sms_send" -> permissions.ensure(
                    listOf(Manifest.permission.SEND_SMS),
                    context.getString(R.string.permission_send_sms_title),
                    context.getString(R.string.permission_send_sms_rationale),
                )
                "call_log" -> permissions.ensure(
                    listOf(Manifest.permission.READ_CALL_LOG),
                    context.getString(R.string.permission_call_log_title),
                    context.getString(R.string.permission_call_log_rationale),
                )
                "calendar_update", "calendar_delete" -> permissions.ensure(
                    listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                    context.getString(R.string.permission_calendar_title),
                    context.getString(R.string.permission_calendar_rationale),
                )
                "audio_record" -> permissions.ensure(
                    listOf(Manifest.permission.RECORD_AUDIO),
                    context.getString(R.string.permission_microphone_title),
                    context.getString(R.string.permission_microphone_rationale),
                )
                "bluetooth_devices" -> permissions.ensureBluetooth()
                "sim_info" -> permissions.ensure(
                    listOf(Manifest.permission.READ_PHONE_STATE),
                    context.getString(R.string.permission_phone_title),
                    context.getString(R.string.permission_phone_rationale),
                )
                "wifi_scan" -> permissions.ensure(
                    buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) {
                            add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        }
                    },
                    context.getString(R.string.permission_wifi_scan_title),
                    context.getString(R.string.permission_wifi_scan_rationale),
                )
                "notification_post", "reminder_schedule" -> permissions.ensureNotifications()
                "quick_toggle" -> permissions.ensureBluetooth()
                "files_save_download" -> if (Build.VERSION.SDK_INT < 29) {
                    permissions.ensure(
                        listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        context.getString(R.string.permission_storage_title),
                        context.getString(R.string.permission_storage_rationale),
                    )
                }
            }
            refresh()
        }
    }

    fun openSpecialAccess(kind: String) {
        val intent = when (kind) {
            "all_files" -> allFiles.requestIntent()
            "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(
                Uri.parse("package:${context.packageName}"),
            )
            "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "install_apps" -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(
                Uri.parse("package:${context.packageName}"),
            )
            else -> return
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        refresh()
    }

    fun adbGrantCommand(): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun connectSaf(uri: Uri) {
        viewModelScope.launch {
            runCatching { saf.connect(uri) }
            refresh()
        }
    }

    fun disconnectSaf() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(safTreeUri = null) }
        }
    }

    private fun accessFor(tool: AgentTool, settings: AppSettings): ToolAccess {
        return when (tool.name) {
            "camera_capture", "flashlight" ->
                if (granted(Manifest.permission.CAMERA)) ToolAccess.READY else ToolAccess.NEEDS_PERMISSION
            "location_current" ->
                if (granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                    granted(Manifest.permission.ACCESS_FINE_LOCATION)
                ) {
                    ToolAccess.READY
                } else {
                    ToolAccess.NEEDS_PERMISSION
                }
            "calendar_list" ->
                if (granted(Manifest.permission.READ_CALENDAR)) ToolAccess.READY
                else ToolAccess.NEEDS_PERMISSION
            "calendar_create" ->
                if (granted(Manifest.permission.READ_CALENDAR) &&
                    granted(Manifest.permission.WRITE_CALENDAR)
                ) {
                    ToolAccess.READY
                } else {
                    ToolAccess.NEEDS_PERMISSION
                }
            "contacts_search" ->
                if (granted(Manifest.permission.READ_CONTACTS)) ToolAccess.READY
                else ToolAccess.NEEDS_PERMISSION
            "contact_create" ->
                if (granted(Manifest.permission.WRITE_CONTACTS)) ToolAccess.READY
                else ToolAccess.NEEDS_PERMISSION
            "sms_list" ->
                if (granted(Manifest.permission.READ_SMS)) ToolAccess.READY
                else ToolAccess.NEEDS_PERMISSION
            "sms_send" ->
                if (granted(Manifest.permission.SEND_SMS)) ToolAccess.READY
                else ToolAccess.NEEDS_PERMISSION
            "call_log" ->
                if (granted(Manifest.permission.READ_CALL_LOG)) ToolAccess.READY
                else ToolAccess.NEEDS_PERMISSION
            "calendar_update", "calendar_delete" ->
                if (granted(Manifest.permission.READ_CALENDAR) &&
                    granted(Manifest.permission.WRITE_CALENDAR)
                ) {
                    ToolAccess.READY
                } else {
                    ToolAccess.NEEDS_PERMISSION
                }
            "audio_record" ->
                if (granted(Manifest.permission.RECORD_AUDIO)) ToolAccess.READY
                else ToolAccess.NEEDS_PERMISSION
            "bluetooth_devices" ->
                if (Build.VERSION.SDK_INT < 31 ||
                    granted(Manifest.permission.BLUETOOTH_CONNECT)
                ) {
                    ToolAccess.READY
                } else {
                    ToolAccess.NEEDS_PERMISSION
                }
            "sim_info" ->
                if (granted(Manifest.permission.READ_PHONE_STATE)) ToolAccess.READY
                else ToolAccess.NEEDS_PERMISSION
            "wifi_scan" ->
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.NEARBY_WIFI_DEVICES))
                ) {
                    ToolAccess.READY
                } else {
                    ToolAccess.NEEDS_PERMISSION
                }
            "app_install" ->
                if (context.packageManager.canRequestPackageInstalls()) ToolAccess.READY
                else ToolAccess.NEEDS_SETTINGS
            "notification_post", "reminder_schedule" ->
                if (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    ToolAccess.READY
                } else {
                    ToolAccess.NEEDS_PERMISSION
                }
            "files_save_download" ->
                if (Build.VERSION.SDK_INT >= 29 ||
                    granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                ) {
                    ToolAccess.READY
                } else {
                    ToolAccess.NEEDS_PERMISSION
                }
            "screen_snapshot", "screen_action", "airplane_mode", "quick_toggle" ->
                if (accessibilityEnabled()) ToolAccess.READY else ToolAccess.NEEDS_ACCESSIBILITY
            "brightness_set", "display_control" ->
                if (Settings.System.canWrite(context)) ToolAccess.READY else ToolAccess.NEEDS_SETTINGS
            "dnd_set" ->
                if (dndAccess()) ToolAccess.READY else ToolAccess.NEEDS_SETTINGS
            "system_settings" ->
                if (granted(Manifest.permission.WRITE_SECURE_SETTINGS)) ToolAccess.READY
                else ToolAccess.NEEDS_SETTINGS
            "notifications_list", "notification_action" ->
                if (AgentNotificationListener.isEnabled(context)) ToolAccess.READY
                else ToolAccess.NEEDS_SETTINGS
            "apps_usage" ->
                if (usageAccess()) ToolAccess.READY else ToolAccess.NEEDS_SETTINGS
            "web_search" ->
                if (!settings.webSearchApiKeyEncrypted.isNullOrBlank()) ToolAccess.READY
                else ToolAccess.NOT_CONFIGURED
            "mcp_list_tools", "mcp_call_tool" ->
                if (settings.mcpEnabled && settings.mcpServerUrl.isNotBlank()) ToolAccess.READY
                else ToolAccess.NOT_CONFIGURED
            else -> ToolAccess.READY
        }
    }

    private fun usageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any {
            it.contains("AgentAccessibilityService")
        }
    }

    private fun dndAccess(): Boolean {
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return false
        return manager.isNotificationPolicyAccessGranted
    }
}
