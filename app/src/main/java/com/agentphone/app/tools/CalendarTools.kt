package com.agentphone.app.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.PermissionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class CalendarListTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "calendar_list"
    override val description = "List upcoming calendar events for the next 30 days."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.COMMUNICATION
    override val parameters = Schemas.obj(
        properties = buildJsonObject {
            put("days", Schemas.integer("How many days ahead to look (1-90)."))
            put("limit", Schemas.integer("Maximum events to return (1-50)."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.READ_CALENDAR),
            "Calendar access",
            "The agent needs to read your calendar to answer this request. Events are only shared with your configured AI provider.",
        )
        if (!outcome.granted) return ToolResult.error("Calendar read permission was denied.")
        val days = arguments.integer("days", 30).coerceIn(1, 90)
        val limit = arguments.integer("limit", 20).coerceIn(1, 50)
        val begin = System.currentTimeMillis()
        val end = begin + days.toLong() * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val events = mutableListOf<String>()
        runCatching {
            this.context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && events.size < limit) {
                    val title = cursor.getString(1) ?: "(untitled)"
                    val start = cursor.getLong(2)
                    val endTime = cursor.getLong(3)
                    val allDay = cursor.getInt(4) == 1
                    val location = cursor.getString(5)
                    val calendar = cursor.getString(6)
                    events += buildString {
                        append(if (allDay) "All day " else "")
                        append(formatTime(start))
                        if (!allDay) append("–").append(formatTime(endTime))
                        append(" · ").append(title)
                        if (!location.isNullOrBlank()) append(" @ ").append(location)
                        append(" [").append(calendar ?: "calendar").append("]")
                    }
                }
            }
        }.onFailure {
            return ToolResult.error("Could not read calendar: ${it.message}")
        }
        if (events.isEmpty()) return ToolResult.ok("No events in the next $days days.")
        return ToolResult.ok("Upcoming events:\n" + events.joinToString("\n") { "• $it" })
    }

    private fun formatTime(epoch: Long): String =
        DateTimeFormatter.ofPattern("EEE MMM d, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epoch))
}

class CalendarCreateTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "calendar_create"
    override val description =
        "Create a calendar event in the first writable calendar. Times must be ISO local date-time, e.g. 2026-11-05T10:00:00."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.COMMUNICATION
    override val parameters = Schemas.obj(
        required = listOf("title", "start", "end"),
        properties = buildJsonObject {
            put("title", Schemas.string("Event title."))
            put("start", Schemas.string("Start date-time (ISO local, e.g. 2026-11-05T10:00:00)."))
            put("end", Schemas.string("End date-time (ISO local)."))
            put("description", Schemas.string("Optional description."))
            put("location", Schemas.string("Optional location."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            "Calendar access",
            "The agent needs permission to add an event to your calendar.",
        )
        if (!outcome.granted) return ToolResult.error("Calendar write permission was denied.")
        val title = arguments.string("title")
        if (title.isBlank()) return ToolResult.error("An event title is required.")
        val start = parse(arguments.string("start"))
            ?: return ToolResult.error("Could not parse start time. Use 2026-11-05T10:00:00.")
        val end = parse(arguments.string("end"))
            ?: return ToolResult.error("Could not parse end time.")
        if (end <= start) return ToolResult.error("End time must be after start time.")
        val calendar = findWritableCalendar()
            ?: return ToolResult.error("No writable calendar found on this device.")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar.first)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, arguments.string("description"))
            put(CalendarContract.Events.EVENT_LOCATION, arguments.string("location"))
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        }
        val uri = runCatching {
            this.context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrElse { return ToolResult.error("Could not create event: ${it.message}") }
            ?: return ToolResult.error("Calendar provider rejected the event.")
        val id = ContentUris.parseId(uri)
        return ToolResult.ok("Created event #$id '$title' in ${calendar.second}.")
    }

    private fun parse(value: String): Long? = runCatching {
        LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private fun findWritableCalendar(): Pair<Long, String>? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0) to (cursor.getString(1) ?: "calendar")
            }
        }
        return null
    }
}
