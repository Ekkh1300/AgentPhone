package com.agentphone.app.tools

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.agentphone.accessibility.AgentAccessibilityService
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.PermissionCoordinator
import com.agentphone.app.platform.UiActionBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class AirplaneModeTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: UiActionBus,
) : AgentTool {
    override val name = "airplane_mode"
    override val description =
        "Read or toggle airplane mode (all radios off/on). Toggling needs the optional screen-control " +
            "service, because Android 10+ refuses direct airplane changes from apps; the agent taps the " +
            "Quick Settings tile for you."
    override val riskLevel = RiskLevel.DANGEROUS
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("action"),
        properties = buildJsonObject {
            put(
                "action",
                Schemas.string("Action to perform.", listOf("status", "on", "off", "toggle")),
            )
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val action = arguments.string("action", "status")
        val current = isOn()
        if (action == "status") return ToolResult.ok(stateLine(current))
        val target = when (action) {
            "on" -> true
            "off" -> false
            "toggle" -> !current
            else -> return ToolResult.error("Unknown airplane action: $action")
        }
        if (target == current) return ToolResult.ok("Airplane mode was already ${if (target) "on" else "off"}.")
        val service = AgentAccessibilityService.instance
            ?: run {
                bus.launch(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
                return ToolResult.error(
                    "Android 10 and newer refuse direct airplane changes from apps, even with adb grants. " +
                        "I opened the airplane settings page so you can flip it, or enable AgentPhone screen " +
                        "control in Settings > Accessibility and I will tap the Quick Settings tile for you.",
                )
            }
        if (!service.openQuickSettings()) {
            return ToolResult.error("Could not open Quick Settings.")
        }
        if (!awaitQuickSettings(service)) {
            return ToolResult.error(
                "Quick Settings did not open. Open it once manually and ask again.",
            )
        }
        var after = isOn()
        var clicked = false
        repeat(2) {
            clicked = service.clickQuickSetting("airplane", "پرواز", "هواپیما") || clicked
            delay(1_400)
            after = isOn()
            if (after != current) {
                return ToolResult.ok(stateLine(after) + " Toggled through Quick Settings.")
            }
        }
        return if (!clicked) {
            ToolResult.error(
                "Quick Settings opened but the Airplane tile was not found. Add the Airplane tile to the " +
                    "first page of Quick Settings and ask again.",
            )
        } else {
            ToolResult.error(
                "The Airplane tile was tapped but airplane mode did not change. Android may have blocked " +
                    "it; change it manually in Quick Settings.",
            )
        }
    }

    private suspend fun awaitQuickSettings(service: AgentAccessibilityService): Boolean {
        repeat(8) {
            if (service.activePackage()?.contains("systemui") == true) return true
            delay(400)
        }
        return false
    }

    private fun isOn(): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0,
        ) == 1

    private fun stateLine(on: Boolean) = "Airplane mode is ${if (on) "on" else "off"}."
}

class DisplayControlTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: UiActionBus,
) : AgentTool {
    override val name = "display_control"
    override val description =
        "Control the display: auto-rotate on/off (rotation lock), screen timeout and adaptive brightness. " +
            "Requires the Write Settings permission."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("action"),
        properties = buildJsonObject {
            put(
                "action",
                Schemas.string(
                    "What to change.",
                    listOf(
                        "status",
                        "rotation_lock",
                        "rotation_unlock",
                        "timeout",
                        "auto_brightness_on",
                        "auto_brightness_off",
                    ),
                ),
            )
            put("seconds", Schemas.integer("Screen timeout in seconds for action=timeout.", 15, 1800))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val action = arguments.string("action", "status")
        if (action == "status") return ToolResult.ok(describe())
        val resolver = this.context.contentResolver
        if (!Settings.System.canWrite(this.context)) {
            bus.launch(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${this@DisplayControlTool.context.packageName}")
                },
            )
            return ToolResult.error(
                "Write Settings permission is required. I opened the settings page; grant it and ask again.",
            )
        }
        val ok = runCatching {
            when (action) {
                "rotation_lock" -> Settings.System.putInt(
                    resolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    0,
                )
                "rotation_unlock" -> Settings.System.putInt(
                    resolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    1,
                )
                "timeout" -> {
                    val seconds = arguments.integer("seconds", 60).coerceIn(15, 1800)
                    Settings.System.putInt(
                        resolver,
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        seconds * 1_000,
                    )
                }
                "auto_brightness_on" -> Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                )
                "auto_brightness_off" -> Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                else -> return ToolResult.error("Unknown display action: $action")
            }
        }.getOrElse { return ToolResult.error("Android blocked the change: ${it.message}") }
        return if (ok) {
            ToolResult.ok("$action applied. ${describe()}")
        } else {
            ToolResult.error("Android rejected the display change.")
        }
    }

    private fun describe(): String {
        val resolver = context.contentResolver
        val rotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        val timeoutMs = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 30_000)
        val brightnessMode = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        return buildString {
            append("Auto-rotate: ${if (rotate == 1) "on" else "off (rotation locked)"}. ")
            append("Screen timeout: ${timeoutMs / 1_000}s. ")
            append(
                "Adaptive brightness: " +
                    if (brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "on" else "off",
            )
            append(". Brightness: ${if (brightness >= 0) "${brightness * 100 / 255}%" else "unknown"}.")
        }
    }
}

class RingerModeTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: UiActionBus,
) : AgentTool {
    override val name = "ringer_mode"
    override val description =
        "Read or change the ringer mode (normal, vibrate, silent). Silent needs Do Not Disturb access."
    override val riskLevel = RiskLevel.DANGEROUS
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("action"),
        properties = buildJsonObject {
            put(
                "action",
                Schemas.string(
                    "What to do.",
                    listOf("status", "normal", "vibrate", "silent"),
                ),
            )
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val audio = this.context.getSystemService(AudioManager::class.java)
            ?: return ToolResult.error("Audio service unavailable.")
        val action = arguments.string("action", "status")
        if (action == "status") return ToolResult.ok(stateLine(audio.ringerMode))
        val target = when (action) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return ToolResult.error("Unknown ringer action: $action")
        }
        if (target == AudioManager.RINGER_MODE_SILENT) {
            val notifications = this.context.getSystemService(NotificationManager::class.java)
            if (notifications?.isNotificationPolicyAccessGranted != true) {
                bus.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                return ToolResult.error(
                    "Silent mode needs Do Not Disturb access. I opened the access screen; grant it and ask again.",
                )
            }
        }
        runCatching { audio.ringerMode = target }.onFailure {
            bus.launch(Intent(Settings.ACTION_SOUND_SETTINGS))
            return ToolResult.error(
                "Android blocked the ringer change (${it.message}). Some devices require Do Not Disturb access.",
            )
        }
        return if (audio.ringerMode == target) {
            ToolResult.ok(stateLine(audio.ringerMode))
        } else {
            ToolResult.error("The ringer mode did not change; the device may block silent mode.")
        }
    }

    private fun stateLine(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "Ringer is silent."
        AudioManager.RINGER_MODE_VIBRATE -> "Ringer is on vibrate."
        else -> "Ringer is on normal."
    }
}

class MediaControlTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {
    override val name = "media_control"
    override val description =
        "Send media key presses to the active player: play/pause, play, pause, next, previous or stop."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("action"),
        properties = buildJsonObject {
            put(
                "action",
                Schemas.string(
                    "Media command.",
                    listOf("play_pause", "play", "pause", "next", "previous", "stop"),
                ),
            )
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val audio = this.context.getSystemService(AudioManager::class.java)
            ?: return ToolResult.error("Audio service unavailable.")
        val action = arguments.string("action", "play_pause")
        val code = when (action) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return ToolResult.error("Unknown media action: $action")
        }
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }.onFailure {
            return ToolResult.error("Could not send the media key: ${it.message}")
        }
        return ToolResult.ok("Sent media key '$action' to the active player.")
    }
}

class QuickToggleTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: UiActionBus,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "quick_toggle"
    override val description =
        "Read or toggle system radios and modes: Wi-Fi, Bluetooth, mobile data, hotspot, location, " +
            "airplane mode, Do Not Disturb and battery saver. Android 10+ blocks direct radio changes from " +
            "apps, so when the optional screen-control service is enabled the agent taps the matching " +
            "Quick Settings tile for you."
    override val riskLevel = RiskLevel.DANGEROUS
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("target", "action"),
        properties = buildJsonObject {
            put(
                "target",
                Schemas.string(
                    "What to control.",
                    listOf(
                        "wifi", "bluetooth", "mobile_data", "hotspot", "location",
                        "airplane", "dnd", "battery_saver",
                    ),
                ),
            )
            put("action", Schemas.string("Action to perform.", listOf("status", "on", "off", "toggle")))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val target = arguments.string("target")
        val action = arguments.string("action", "status")
        val labels = labelsFor(target)
            ?: return ToolResult.error("Unknown quick toggle target: $target")
        if (target == "bluetooth" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val outcome = permissions.ensureBluetooth()
            if (!outcome.granted) {
                return ToolResult.error(
                    "Bluetooth permission was denied, so the app cannot read or verify Bluetooth state.",
                )
            }
        }
        val current = readState(target)
        if (action == "status") return ToolResult.ok(stateLine(target, current))
        val desired = when (action) {
            "on" -> true
            "off" -> false
            "toggle" -> current?.let { !it }
                ?: return ToolResult.error(
                    "The current $target state is unknown on this device; use action=on or action=off.",
                )
            else -> return ToolResult.error("Unknown quick toggle action: $action")
        }
        if (current != null && current == desired) {
            return ToolResult.ok("$target was already ${if (desired) "on" else "off"}.")
        }
        val service = AgentAccessibilityService.instance
            ?: run {
                bus.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return ToolResult.error(
                    "Android blocks apps from changing $target directly. Enable AgentPhone screen control " +
                        "in Settings > Accessibility and I will tap the Quick Settings tile for you.",
                )
            }
        if (!service.openQuickSettings()) return ToolResult.error("Could not open Quick Settings.")
        if (!awaitQuickSettings(service)) {
            return ToolResult.error("Quick Settings did not open. Open it once manually and ask again.")
        }
        var clicked = false
        suspend fun attempt(): Boolean {
            clicked = service.clickQuickSetting(*labels) || clicked
            delay(1_400)
            if (readState(target) == desired) return true
            if (target == "wifi" || target == "mobile_data") {
                if (service.clickQuickSetting("Internet", "اینترنت")) {
                    delay(900)
                    clicked = service.clickQuickSetting(*labels) || clicked
                    delay(1_500)
                    val changed = readState(target) == desired
                    service.pressBack()
                    delay(250)
                    return changed
                }
            }
            return false
        }
        repeat(2) {
            if (attempt()) {
                delay(200)
                service.pressBack()
                return ToolResult.ok(
                    "$target is now ${if (desired) "on" else "off"} (toggled through Quick Settings).",
                )
            }
        }
        service.pressBack()
        return when {
            !clicked -> ToolResult.error(
                "Quick Settings opened but the $target tile was not found. Add the ${labels.first()} tile " +
                    "to the first page of Quick Settings and ask again.",
            )
            current == null -> ToolResult.ok(
                "Tapped the $target tile. This device does not expose its state to apps, so verify visually.",
            )
            else -> ToolResult.error(
                "The $target tile was tapped but the state did not change. Android may have blocked it; " +
                    "change it manually in Quick Settings.",
            )
        }
    }

    private suspend fun awaitQuickSettings(service: AgentAccessibilityService): Boolean {
        repeat(8) {
            if (service.activePackage()?.contains("systemui") == true) return true
            delay(400)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun readState(target: String): Boolean? = runCatching {
        when (target) {
            "wifi" -> context.getSystemService(WifiManager::class.java)?.isWifiEnabled
            "bluetooth" -> context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
            "mobile_data" -> Settings.Global.getInt(
                context.contentResolver,
                "mobile_data",
                1,
            ) == 1
            "hotspot" -> context.getSystemService(WifiManager::class.java)?.let { wifi ->
                wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as? Boolean
            }
            "location" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled
            } else {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF,
                ) != Settings.Secure.LOCATION_MODE_OFF
            }
            "airplane" -> Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) == 1
            "dnd" -> context.getSystemService(NotificationManager::class.java)
                ?.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            "battery_saver" -> context.getSystemService(PowerManager::class.java)?.isPowerSaveMode
            else -> null
        }
    }.getOrNull()

    private fun labelsFor(target: String): Array<String>? = when (target) {
        "wifi" -> arrayOf("Wi-Fi", "WiFi", "وای فای", "وایفای")
        "bluetooth" -> arrayOf("Bluetooth", "بلوتوث")
        "mobile_data" -> arrayOf("Mobile data", "Cellular data", "داده همراه", "اینترنت همراه")
        "hotspot" -> arrayOf("Hotspot", "Wi-Fi hotspot", "نقطه اتصال", "هاتاسپات")
        "location" -> arrayOf("Location", "موقعیت مکانی", "مکان")
        "airplane" -> arrayOf("Airplane", "Aeroplane", "پرواز", "هواپیما")
        "dnd" -> arrayOf("Do Not Disturb", "مزاحم نشوید", "مزاحم")
        "battery_saver" -> arrayOf("Battery Saver", "Battery saver", "صرفه جویی در باتری", "ذخیره باتری")
        else -> null
    }

    private fun stateLine(target: String, value: Boolean?): String = when (value) {
        null -> "$target state is unknown on this device."
        true -> "$target is on."
        false -> "$target is off."
    }
}

class SystemSettingsTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {
    override val name = "system_settings"
    override val description =
        "Read or write Android system/secure/global settings by key. Writing secure or global keys needs " +
            "WRITE_SECURE_SETTINGS, which the user grants once with adb. Dangerous: wrong keys can change " +
            "system behavior. Action=grant_info returns the exact adb command."
    override val riskLevel = RiskLevel.DANGEROUS
    override val category = ToolCategory.ADVANCED
    override val parameters = Schemas.obj(
        required = listOf("action"),
        properties = buildJsonObject {
            put(
                "action",
                Schemas.string("What to do.", listOf("get", "set", "delete", "list", "grant_info")),
            )
            put("namespace", Schemas.string("Settings namespace.", listOf("system", "secure", "global")))
            put("key", Schemas.string("Setting key, e.g. animator_duration_scale."))
            put("value", Schemas.string("New value for action=set. Use 1/0 for boolean settings."))
            put("query", Schemas.string("Optional key substring filter for action=list."))
            put("limit", Schemas.integer("Maximum keys for action=list (1-300).", 1, 300))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val action = arguments.string("action", "get")
        val namespace = arguments.string("namespace", "secure")
        if (namespace !in setOf("system", "secure", "global")) {
            return ToolResult.error("namespace must be system, secure or global.")
        }
        if (action == "grant_info") {
            return ToolResult.ok(
                "Run this once from a computer with USB debugging:\n" +
                    "adb shell pm grant ${this.context.packageName} android.permission.WRITE_SECURE_SETTINGS\n" +
                    "Then reload AgentPhone. Some keys still require a system app or root.",
            )
        }
        val key = arguments.string("key").trim()
        if (action in setOf("get", "set", "delete") && key.isBlank()) {
            return ToolResult.error("A settings key is required.")
        }
        if (key.isNotEmpty() && !KEY_PATTERN.matches(key)) {
            return ToolResult.error("Invalid settings key: $key")
        }
        if (action in setOf("set", "delete") && !canWrite(namespace)) {
            return ToolResult.error(
                "Missing permission for $namespace settings. " +
                    if (namespace == "system") {
                        "Grant Write Settings in Tools & Permissions > Advanced access."
                    } else {
                        "Run: adb shell pm grant ${this.context.packageName} " +
                            "android.permission.WRITE_SECURE_SETTINGS"
                    },
            )
        }
        return when (action) {
            "get" -> {
                val value = getString(namespace, key)
                ToolResult.ok(if (value == null) "$namespace/$key is unset." else "$namespace/$key = $value")
            }
            "set" -> {
                val value = arguments.string("value")
                val ok = runCatching { putString(namespace, key, value) }.getOrDefault(false)
                if (ok) ToolResult.ok("Set $namespace/$key = $value.")
                else ToolResult.error("Android rejected the write to $namespace/$key.")
            }
            "delete" -> {
                val ok = runCatching { putString(namespace, key, null) }.getOrDefault(false)
                if (ok) ToolResult.ok("Removed $namespace/$key.")
                else ToolResult.error("Android rejected deleting $namespace/$key.")
            }
            "list" -> {
                val query = arguments.string("query")
                val limit = arguments.integer("limit", 80).coerceIn(1, 300)
                val all = runCatching { all(namespace) }.getOrDefault(emptyMap())
                val rows = all.entries
                    .filter { query.isBlank() || it.key.contains(query, ignoreCase = true) }
                    .sortedBy { it.key }
                    .take(limit)
                if (rows.isEmpty()) {
                    ToolResult.ok("No $namespace settings match '$query'.")
                } else {
                    ToolResult.ok(
                        buildString {
                            append("$namespace settings (${rows.size} shown of ${all.size}):\n")
                            rows.forEach { (name, value) ->
                                append("• ").append(name).append(" = ").append(value.take(160)).append('\n')
                            }
                        }.trimEnd(),
                    )
                }
            }
            else -> ToolResult.error("Unknown settings action: $action")
        }
    }

    private fun canWrite(namespace: String): Boolean = if (namespace == "system") {
        Settings.System.canWrite(context)
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getString(namespace: String, key: String): String? = when (namespace) {
        "system" -> Settings.System.getString(context.contentResolver, key)
        "global" -> Settings.Global.getString(context.contentResolver, key)
        else -> Settings.Secure.getString(context.contentResolver, key)
    }

    private fun putString(namespace: String, key: String, value: String?): Boolean = when (namespace) {
        "system" -> Settings.System.putString(context.contentResolver, key, value)
        "global" -> Settings.Global.putString(context.contentResolver, key, value)
        else -> Settings.Secure.putString(context.contentResolver, key, value)
    }

    private fun all(namespace: String): Map<String, String> {
        val uri = when (namespace) {
            "system" -> Settings.System.CONTENT_URI
            "global" -> Settings.Global.CONTENT_URI
            else -> Settings.Secure.CONTENT_URI
        }
        val result = LinkedHashMap<String, String>()
        runCatching {
            context.contentResolver.query(uri, arrayOf("name", "value"), null, null, "name ASC")
                ?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    val valueIndex = cursor.getColumnIndex("value")
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex) ?: continue
                        result[name] = cursor.getString(valueIndex) ?: "(null)"
                    }
                }
        }
        return result
    }

    private companion object {
        val KEY_PATTERN = Regex("[A-Za-z0-9_.]{1,120}")
    }
}
