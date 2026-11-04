package com.agentphone.app.tools

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.Intent
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.PermissionCoordinator
import com.agentphone.app.platform.UiActionBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class FlashlightTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "flashlight"
    override val description = "Turn the camera flashlight (torch) on or off."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.DEVICE
    override val parameters = Schemas.obj(
        required = listOf("on"),
        properties = buildJsonObject {
            put("on", Schemas.boolean("true to turn the flashlight on, false to turn it off."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensureCamera()
        if (!outcome.granted) return ToolResult.error("Camera permission is needed for the flashlight.")
        val manager = this.context.getSystemService(CameraManager::class.java)
            ?: return ToolResult.error("Camera service is unavailable.")
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return ToolResult.error("This device has no flashlight.")
        val on = arguments.bool("on", true)
        runCatching { manager.setTorchMode(cameraId, on) }
            .getOrElse { return ToolResult.error("Could not change the flashlight: ${it.message}") }
        return ToolResult.ok("Flashlight ${if (on) "on" else "off"}.")
    }
}

class VolumeControlTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {
    override val name = "volume_control"
    override val description = "Read or change media/ring/alarm/notification volume."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("action"),
        properties = buildJsonObject {
            put(
                "action",
                Schemas.string(
                    "What to do.",
                    listOf("up", "down", "mute", "unmute", "set", "status"),
                ),
            )
            put("stream", Schemas.string("Audio stream.", listOf("music", "ring", "alarm", "notification")))
            put("percent", Schemas.integer("Target volume 0-100 for action=set.", 0, 100))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val audio = this.context.getSystemService(AudioManager::class.java)
            ?: return ToolResult.error("Audio service unavailable.")
        val stream = when (arguments.string("stream", "music")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val action = arguments.string("action", "status")
        when (action) {
            "up" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "set" -> {
                val percent = arguments.integer("percent", 50).coerceIn(0, 100)
                val max = audio.getStreamMaxVolume(stream)
                val index = Math.round(percent / 100f * max)
                audio.setStreamVolume(stream, index, AudioManager.FLAG_SHOW_UI)
            }
            "status" -> Unit
            else -> return ToolResult.error("Unknown volume action: $action")
        }
        val current = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        return ToolResult.ok(
            "Volume ($action): $current/$max (${(current * 100 / max)}%).",
        )
    }
}

class BrightnessTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: UiActionBus,
) : AgentTool {
    override val name = "brightness_set"
    override val description =
        "Set screen brightness (0-100). Requires the special Write Settings permission."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("percent"),
        properties = buildJsonObject {
            put("percent", Schemas.integer("Brightness 0-100.", 0, 100))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        if (!Settings.System.canWrite(this.context)) {
            bus.launch(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${this@BrightnessTool.context.packageName}")
                },
            )
            return ToolResult.error(
                "Write Settings permission is required. I opened the settings page; grant it and ask again.",
            )
        }
        val percent = arguments.integer("percent", 50).coerceIn(1, 100)
        val value = (percent / 100f * 255).toInt().coerceIn(1, 255)
        Settings.System.putInt(
            this.context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val ok = Settings.System.putInt(
            this.context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            value,
        )
        return if (ok) ToolResult.ok("Set screen brightness to $percent%.") else {
            ToolResult.error("Android rejected the brightness change.")
        }
    }
}

class DndTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: UiActionBus,
) : AgentTool {
    override val name = "dnd_set"
    override val description =
        "Change Do Not Disturb mode (off, priority, alarms or total silence). Requires DND access."
    override val riskLevel = RiskLevel.DANGEROUS
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("mode"),
        properties = buildJsonObject {
            put("mode", Schemas.string("DND mode.", listOf("off", "priority", "alarms", "none")))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val manager = this.context.getSystemService(NotificationManager::class.java)
            ?: return ToolResult.error("Notification service unavailable.")
        if (!manager.isNotificationPolicyAccessGranted) {
            bus.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return ToolResult.error(
                "Do Not Disturb access is not granted. I opened the access screen; grant it and ask again.",
            )
        }
        val filter = when (arguments.string("mode", "off")) {
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> NotificationManager.INTERRUPTION_FILTER_ALL
        }
        manager.setInterruptionFilter(filter)
        return ToolResult.ok("Do Not Disturb mode changed to ${arguments.string("mode", "off")}.")
    }
}
