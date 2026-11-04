package com.agentphone.app.tools

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.UiActionBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import javax.inject.Inject

class ClipboardReadTool @Inject constructor(
    private val clipboard: ClipboardSupport,
) : AgentTool {
    override val name = "clipboard_read"
    override val description = "Read the current text from the Android clipboard. Requires the app to be in the foreground."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.DEVICE
    override val parameters = Schemas.obj(properties = buildJsonObject { })

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        runCatching { ToolResult.ok("Clipboard content:\n${clipboard.read()}") }
            .getOrElse { ToolResult.error(it.message ?: "Could not read the clipboard.") }
}

class ClipboardWriteTool @Inject constructor(
    private val clipboard: ClipboardSupport,
) : AgentTool {
    override val name = "clipboard_write"
    override val description = "Copy text to the Android clipboard."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.DEVICE
    override val parameters = Schemas.obj(
        required = listOf("text"),
        properties = buildJsonObject { put("text", Schemas.string("Text to copy.")) },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        runCatching {
            val text = arguments.string("text")
            clipboard.write(text)
            ToolResult.ok("Copied ${text.length} characters to the clipboard.")
        }.getOrElse { ToolResult.error(it.message ?: "Could not write the clipboard.") }
}

abstract class BaseIntentTool : AgentTool {
    override val category = ToolCategory.COMMUNICATION
    override val riskLevel = RiskLevel.WRITE
}

class ShareTextTool @Inject constructor(
    private val bus: UiActionBus,
) : BaseIntentTool() {
    override val name = "share_text"
    override val description = "Open the Android share sheet with text."
    override val parameters = Schemas.obj(
        required = listOf("text"),
        properties = buildJsonObject {
            put("text", Schemas.string("Text to share."))
            put("title", Schemas.string("Optional chooser title."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val text = arguments.string("text")
        if (text.isBlank()) return ToolResult.error("Nothing to share.")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, arguments.string("title", "Shared from AgentPhone"))
        }
        bus.launch(Intent.createChooser(send, arguments.string("title", "Share with")))
        return ToolResult.ok("Opened the share sheet for ${text.length} characters.")
    }
}

class OpenUrlTool @Inject constructor(
    private val bus: UiActionBus,
) : BaseIntentTool() {
    override val name = "open_url"
    override val description = "Open an http(s) URL in the user's browser."
    override val parameters = Schemas.obj(
        required = listOf("url"),
        properties = buildJsonObject { put("url", Schemas.string("The https:// URL to open.")) },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val url = arguments.string("url")
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            return ToolResult.error("Only http:// and https:// URLs can be opened.")
        }
        bus.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return ToolResult.ok("Opened $url in the browser.")
    }
}

class OpenMapsTool @Inject constructor(
    private val bus: UiActionBus,
) : BaseIntentTool() {
    override val name = "open_maps"
    override val description = "Open a maps app showing a place, address or coordinates."
    override val parameters = Schemas.obj(
        required = listOf("query"),
        properties = buildJsonObject {
            put("query", Schemas.string("Address, place name or 'lat,lng'."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val query = arguments.string("query")
        if (query.isBlank()) return ToolResult.error("A place or address is required.")
        val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        bus.launch(Intent(Intent.ACTION_VIEW, uri))
        return ToolResult.ok("Opened maps for: $query")
    }
}

class DialNumberTool @Inject constructor(
    private val bus: UiActionBus,
) : BaseIntentTool() {
    override val name = "dial_number"
    override val description =
        "Open the phone dialer with a number pre-filled. Never places a call automatically."
    override val parameters = Schemas.obj(
        required = listOf("number"),
        properties = buildJsonObject {
            put("number", Schemas.string("Phone number, including country code when known."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val number = arguments.string("number")
        if (number.isBlank()) return ToolResult.error("A phone number is required.")
        bus.launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
        return ToolResult.ok("Opened the dialer with $number. The user must press call.")
    }
}

class OpenAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: UiActionBus,
) : BaseIntentTool() {
    override val name = "open_app"
    override val description = "Launch an installed app by package name or visible app label."
    override val parameters = Schemas.obj(
        required = listOf("query"),
        properties = buildJsonObject {
            put("query", Schemas.string("Package name (preferred) or app label."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val query = arguments.string("query")
        if (query.isBlank()) return ToolResult.error("A package name or app label is required.")
        val pm = this.context.packageManager
        var intent = pm.getLaunchIntentForPackage(query)
        if (intent == null) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val matches = pm.queryIntentActivities(launcher, 0).filter { info ->
                val label = info.loadLabel(pm).toString()
                label.contains(query, ignoreCase = true) ||
                    info.activityInfo.packageName.contains(query, ignoreCase = true)
            }
            if (matches.size == 1) {
                val info = matches.first()
                intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(info.activityInfo.packageName, info.activityInfo.name)
                }
            } else if (matches.size > 1) {
                return ToolResult.error(
                    "Multiple apps match '$query': " +
                        matches.take(6).joinToString { it.loadLabel(pm).toString() },
                )
            }
        }
        if (intent == null) return ToolResult.error("No launchable app matched '$query'.")
        bus.launch(intent)
        return ToolResult.ok("Launched ${intent.`package` ?: query}.")
    }
}

class OpenSettingsTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: UiActionBus,
) : BaseIntentTool() {
    override val name = "open_settings"
    override val description =
        "Open any Android settings page or quick-settings panel: app, notifications, Wi-Fi, internet, " +
            "Bluetooth, airplane, hotspot, NFC, sound, display, battery, apps, security, developer options, " +
            "date, language, keyboard, storage, location, DND access and more."
    override val parameters = Schemas.obj(
        required = listOf("page"),
        properties = buildJsonObject {
            put(
                "page",
                Schemas.string(
                    "Settings page to open.",
                    listOf(
                        "app", "notifications", "accessibility", "dnd", "write_settings",
                        "location", "wifi", "internet", "bluetooth", "airplane", "hotspot",
                        "nfc", "mobile_data", "sound", "display", "night_display",
                        "auto_rotate", "battery", "apps", "security", "developer", "date",
                        "language", "keyboard", "storage", "usage_access",
                        "notification_listener", "default_apps", "vpn", "cast", "home",
                        "print", "device_info", "privacy",
                    ),
                ),
            )
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val page = arguments.string("page", "app")
        val self = this.context
        val packageUri = Uri.parse("package:${self.packageName}")
        val intent = when (page) {
            "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, self.packageName)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(packageUri)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "wifi" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
            "internet", "hotspot" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                } else {
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                }
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "nfc" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_NFC)
                } else {
                    Intent(Settings.ACTION_NFC_SETTINGS)
                }
            "mobile_data" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
                } else {
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                }
            "sound" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_VOLUME)
                } else {
                    Intent(Settings.ACTION_SOUND_SETTINGS)
                }
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "night_display" -> Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS)
            "auto_rotate" -> Intent(Settings.ACTION_AUTO_ROTATE_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "apps" -> Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
            "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "language" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            "keyboard" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "default_apps" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            "vpn" -> Intent(Settings.ACTION_VPN_SETTINGS)
            "cast" -> Intent(Settings.ACTION_CAST_SETTINGS)
            "home" -> Intent(Settings.ACTION_HOME_SETTINGS)
            "print" -> Intent(Settings.ACTION_PRINT_SETTINGS)
            "device_info" -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            "privacy" -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
            else -> return ToolResult.error("Unknown settings page: $page")
        }
        bus.launch(intent)
        return ToolResult.ok("Opened the $page settings page.")
    }
}
