package com.competra.data.scheduler

import com.competra.data.database.entity.CompetitionNotificationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderNotificationSchedulerTest {

    private val startReminder = REMINDER_WINDOWS.single { it.type == CompetitionNotificationType.START_REMINDER }
    private val dayBefore = REMINDER_WINDOWS.single { it.type == CompetitionNotificationType.DAY_BEFORE_REMINDER }

    @Test
    fun `start reminder window boundaries`() {
        val now = 1_000_000L
        val bounds = reminderWindowBounds(now, startReminder)

        assertEquals(now + 50 * 60_000L, bounds.first)
        assertEquals(now + 60 * 60_000L, bounds.last)
    }

    @Test
    fun `competition exactly 55 minutes before start is inside start reminder window`() {
        val now = 1_000_000L
        val startDate = now + 55 * 60_000L

        assertTrue(startDate in reminderWindowBounds(now, startReminder))
    }

    @Test
    fun `competition 40 minutes before start is outside start reminder window`() {
        val now = 1_000_000L
        val startDate = now + 40 * 60_000L

        assertFalse(startDate in reminderWindowBounds(now, startReminder))
    }

    @Test
    fun `competition 90 minutes before start is outside start reminder window`() {
        val now = 1_000_000L
        val startDate = now + 90 * 60_000L

        assertFalse(startDate in reminderWindowBounds(now, startReminder))
    }

    @Test
    fun `day-before window boundaries`() {
        val now = 1_000_000L
        val bounds = reminderWindowBounds(now, dayBefore)

        assertEquals(now + (23 * 60 + 50) * 60_000L, bounds.first)
        assertEquals(now + 24 * 60 * 60_000L, bounds.last)
    }

    @Test
    fun `competition exactly 24 hours before start is inside day-before window`() {
        val now = 1_000_000L
        val startDate = now + 24 * 60 * 60_000L

        assertTrue(startDate in reminderWindowBounds(now, dayBefore))
    }

    @Test
    fun `competition 12 hours before start is outside day-before window`() {
        val now = 1_000_000L
        val startDate = now + 12 * 60 * 60_000L

        assertFalse(startDate in reminderWindowBounds(now, dayBefore))
    }
}
