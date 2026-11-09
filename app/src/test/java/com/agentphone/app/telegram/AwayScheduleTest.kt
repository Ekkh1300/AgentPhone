package com.agentphone.app.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class AwayScheduleTest {

    private fun settings(
        startMinute: Int,
        endMinute: Int,
        days: Int = AwaySettings.ALL_DAYS,
    ) = AwaySettings(
        enabled = true,
        scheduleEnabled = true,
        scheduleStartMinute = startMinute,
        scheduleEndMinute = endMinute,
        scheduleDays = days,
    )

    @Test
    fun scheduleDisabledAlwaysAllows() {
        val away = AwaySettings(scheduleEnabled = false)
        assertTrue(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 3, 0)))
        assertTrue(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 14, 0)))
    }

    @Test
    fun insideSimpleWindow() {
        val away = settings(startMinute = 0, endMinute = 8 * 60)
        assertTrue(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 3, 30)))
        assertFalse(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 8, 0)))
        assertFalse(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 10, 0)))
    }

    @Test
    fun supportsWindowAcrossMidnight() {
        val away = settings(startMinute = 22 * 60, endMinute = 6 * 60)
        assertTrue(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 23, 0)))
        assertTrue(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 5, 59)))
        assertFalse(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 6, 0)))
        assertFalse(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 12, 0)))
    }

    @Test
    fun equalStartAndEndMeansAllDay() {
        val away = settings(startMinute = 5 * 60, endMinute = 5 * 60)
        assertTrue(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 0, 0)))
        assertTrue(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 12, 0)))
        assertTrue(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 23, 59)))
    }

    @Test
    fun disabledDayBlocksWholeDay() {
        // Monday only.
        val away = settings(startMinute = 0, endMinute = 24 * 60 - 1, days = 1 shl 0)
        assertTrue(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 12, 0))) // Monday
        assertFalse(away.insideSchedule(LocalDateTime.of(2026, 1, 6, 12, 0))) // Tuesday
        assertTrue(away.dayEnabled(DayOfWeek.MONDAY))
        assertFalse(away.dayEnabled(DayOfWeek.TUESDAY))
    }

    @Test
    fun noDaysSelectedBlocksEverything() {
        val away = settings(startMinute = 0, endMinute = 8 * 60, days = 0)
        assertFalse(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 3, 0)))
    }

    @Test
    fun invalidValuesAreClampedNotCrashed() {
        val away = AwaySettings(
            scheduleEnabled = true,
            scheduleStartMinute = -50,
            scheduleEndMinute = 5_000,
        )
        assertTrue(away.insideSchedule(LocalDateTime.of(2026, 1, 5, 0, 0)))
    }
}
