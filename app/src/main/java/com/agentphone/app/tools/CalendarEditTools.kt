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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private fun parseLocalDateTime(value: String): Long? = runCatching {
    LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrNull()

class CalendarUpdateTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "calendar_update"
    override val description =
        "Update an existing calendar event by id. Change its title, times, description or location. " +
            "Times use ISO local date-time, e.g. 2026-11-05T10:00:00."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.COMMUNICATION
    override val parameters = Schemas.obj(
        required = listOf("event_id"),
        properties = buildJsonObject {
            put("event_id", Schemas.integer("Event id from calendar_list."))
            put("title", Schemas.string("New title (optional)."))
            put("start", Schemas.string("New start date-time (optional)."))
            put("end", Schemas.string("New end date-time (optional)."))
            put("description", Schemas.string("New description (optional)."))
            put("location", Schemas.string("New location (optional)."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            "Calendar access",
            "The agent needs permission to change the event you asked about.",
        )
        if (!outcome.granted) return ToolResult.error("Calendar permission was denied.")
        val id = arguments.integer("event_id", 0).toLong()
        if (id <= 0) return ToolResult.error("A positive event_id is required.")
        val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        var existingTitle: String? = null
        var existingStart: Long? = null
        var existingEnd: Long? = null
        runCatching {
            this.context.contentResolver.query(
                eventUri,
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    existingTitle = cursor.getString(0)
                    if (!cursor.isNull(1)) existingStart = cursor.getLong(1)
                    if (!cursor.isNull(2)) existingEnd = cursor.getLong(2)
                }
            }
        }.onFailure { return ToolResult.error("Could not read the event: ${it.message}") }
        if (existingTitle == null && existingStart == null) {
            return ToolResult.error("No calendar event found with id $id.")
        }
        val values = ContentValues()
        var changed = false
        arguments.optString("title")?.let {
            values.put(CalendarContract.Events.TITLE, it)
            changed = true
        }
        arguments.optString("description")?.let {
            values.put(CalendarContract.Events.DESCRIPTION, it)
            changed = true
        }
        arguments.optString("location")?.let {
            values.put(CalendarContract.Events.EVENT_LOCATION, it)
            changed = true
        }
        val newStart = arguments.optString("start")?.let {
            parseLocalDateTime(it) ?: return ToolResult.error("Could not parse the new start time.")
        }
        val newEnd = arguments.optString("end")?.let {
            parseLocalDateTime(it) ?: return ToolResult.error("Could not parse the new end time.")
        }
        val effectiveStart = newStart ?: existingStart
        val effectiveEnd = newEnd ?: existingEnd
        if (effectiveStart != null && effectiveEnd != null && effectiveEnd <= effectiveStart) {
            return ToolResult.error("End time must be after start time.")
        }
        if (newStart != null) {
            values.put(CalendarContract.Events.DTSTART, newStart)
            changed = true
        }
        if (newEnd != null) {
            values.put(CalendarContract.Events.DTEND, newEnd)
            changed = true
        }
        if (!changed) return ToolResult.error("Provide at least one field to change.")
        if (newStart != null || newEnd != null) {
            values.put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        }
        val rows = runCatching {
            this.context.contentResolver.update(eventUri, values, null, null)
        }.getOrElse { return ToolResult.error("Could not update the event: ${it.message}") }
        if (rows == 0) return ToolResult.error("The calendar provider did not update event $id.")
        val title = arguments.optString("title") ?: existingTitle ?: "event"
        return ToolResult.ok("Updated event #$id '$title'.")
    }
}

class CalendarDeleteTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "calendar_delete"
    override val description = "Delete a calendar event by id. This cannot be undone."
    override val riskLevel = RiskLevel.DANGEROUS
    override val category = ToolCategory.COMMUNICATION
    override val parameters = Schemas.obj(
        required = listOf("event_id"),
        properties = buildJsonObject {
            put("event_id", Schemas.integer("Event id from calendar_list."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            "Calendar access",
            "The agent needs permission to delete the event you selected.",
        )
        if (!outcome.granted) return ToolResult.error("Calendar permission was denied.")
        val id = arguments.integer("event_id", 0).toLong()
        if (id <= 0) return ToolResult.error("A positive event_id is required.")
        val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val title = runCatching {
            this.context.contentResolver.query(
                eventUri,
                arrayOf(CalendarContract.Events.TITLE),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        val rows = runCatching {
            this.context.contentResolver.delete(eventUri, null, null)
        }.getOrElse { return ToolResult.error("Could not delete the event: ${it.message}") }
        if (rows == 0) return ToolResult.error("No calendar event found with id $id.")
        return ToolResult.ok("Deleted event #$id" + (title?.let { " '$it'" } ?: "") + ".")
    }
}
