package com.agentphone.app.telegram

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only free/busy for the next day. Only start/end times are read; titles, attendees and
 * descriptions never leave the device.
 */
@Singleton
class CalendarAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun nextDaySummary(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val now = System.currentTimeMillis()
        val end = now + DAY_MS
        val busy = mutableListOf<String>()
        var allDay = false
        runCatching {
            context.contentResolver.query(
                CalendarContract.Instances.CONTENT_URI,
                arrayOf(
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                ),
                "${CalendarContract.Instances.BEGIN} < ? AND ${CalendarContract.Instances.END} > ?",
                arrayOf(end.toString(), now.toString()),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val formatter = DateFormat.getTimeInstance(DateFormat.SHORT)
                while (cursor.moveToNext() && busy.size < 8) {
                    if (cursor.getInt(2) == 1) {
                        allDay = true
                    } else {
                        busy += "${formatter.format(Date(cursor.getLong(0)))}–" +
                            formatter.format(Date(cursor.getLong(1)))
                    }
                }
            }
        }.getOrNull() ?: return null
        if (busy.isEmpty() && !allDay) return "No calendar events in the next 24 hours."
        return buildString {
            append("Busy in the next 24 hours: ")
            if (allDay) append("all-day event, ")
            append(busy.joinToString(", "))
        }.trimEnd(',', ' ')
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
