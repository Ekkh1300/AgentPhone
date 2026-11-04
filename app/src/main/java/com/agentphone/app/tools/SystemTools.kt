package com.agentphone.app.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.Notifications
import com.agentphone.app.platform.PermissionCoordinator
import com.agentphone.app.platform.UiActionBus
import com.agentphone.app.worker.ReminderWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class AlarmSetTool @Inject constructor(
    private val bus: UiActionBus,
) : AgentTool {
    override val name = "alarm_set"
    override val description = "Open the system clock's new-alarm screen with time and label pre-filled."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("hour", "minute"),
        properties = buildJsonObject {
            put("hour", Schemas.integer("Hour in 24-hour time (0-23).", 0, 23))
            put("minute", Schemas.integer("Minute (0-59).", 0, 59))
            put("label", Schemas.string("Optional alarm label."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val hour = arguments.integer("hour", -1)
        val minute = arguments.integer("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult.error("hour must be 0-23 and minute must be 0-59.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, arguments.string("label", "AgentPhone alarm"))
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        bus.launch(intent)
        return ToolResult.ok("Opened the alarm screen for %02d:%02d.".format(hour, minute))
    }
}

class TimerSetTool @Inject constructor(
    private val bus: UiActionBus,
) : AgentTool {
    override val name = "timer_set"
    override val description = "Open the system clock's timer screen with a duration pre-filled."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("seconds"),
        properties = buildJsonObject {
            put("seconds", Schemas.integer("Timer duration in seconds (1-86400).", 1, 86_400))
            put("label", Schemas.string("Optional timer label."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val seconds = arguments.integer("seconds", 0)
        if (seconds !in 1..86_400) return ToolResult.error("seconds must be between 1 and 86400.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, arguments.string("label", "AgentPhone timer"))
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        bus.launch(intent)
        return ToolResult.ok("Opened a timer for $seconds seconds.")
    }
}

class ReminderScheduleTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "reminder_schedule"
    override val description =
        "Schedule a local notification reminder after a delay using WorkManager. Survives app restarts."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("title", "delay_seconds"),
        properties = buildJsonObject {
            put("title", Schemas.string("Reminder title."))
            put("message", Schemas.string("Reminder body."))
            put("delay_seconds", Schemas.integer("Delay from now in seconds (5-2592000).", 5, 2_592_000))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        if (Build.VERSION.SDK_INT >= 33) {
            val outcome = permissions.ensureNotifications()
            if (!outcome.granted) return ToolResult.error("Notification permission was denied.")
        }
        val title = arguments.string("title")
        if (title.isBlank()) return ToolResult.error("A reminder title is required.")
        val delay = arguments.integer("delay_seconds", 60).coerceIn(5, 2_592_000)
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.toLong(), TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    ReminderWorker.KEY_TITLE to title,
                    ReminderWorker.KEY_MESSAGE to arguments.string("message"),
                    ReminderWorker.KEY_ID to id,
                ),
            )
            .addTag("agent_reminder")
            .build()
        WorkManager.getInstance(this.context).enqueue(request)
        return ToolResult.ok("Scheduled reminder '$title' in $delay seconds.")
    }
}

class NotificationPostTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "notification_post"
    override val description = "Post a notification to the Android notification shade."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("title"),
        properties = buildJsonObject {
            put("title", Schemas.string("Notification title."))
            put("message", Schemas.string("Notification body."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        if (Build.VERSION.SDK_INT >= 33) {
            val outcome = permissions.ensureNotifications()
            if (!outcome.granted) return ToolResult.error("Notification permission was denied.")
        }
        val title = arguments.string("title")
        if (title.isBlank()) return ToolResult.error("A notification title is required.")
        val message = arguments.string("message")
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        Notifications.show(this.context, id, title, message, Notifications.CHANNEL_AGENT)
        return ToolResult.ok("Posted notification '$title'.")
    }
}
