package com.callnotes.util

import com.callnotes.data.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderTimeTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    private fun task(date: String?, time: String? = null, reminded: Long? = null, snoozed: Long? = null) =
        TaskEntity(callId = 0, idx = 0, title = "t", owner = "me", dueDate = date, duePhrase = null, quote = null,
            dueTime = time, remindedAt = reminded, snoozedUntil = snoozed)
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun dateOnlyRemindsAtTheMorningHour() =
        assertEquals(at(2026, 10, 24, 9, 0), reminderAt(task("2026-10-24"), zone, 9))

    @Test fun timeOfDayIsUsed() =
        assertEquals(at(2026, 10, 24, 17, 30), reminderAt(task("2026-10-24", "17:30"), zone, 9))

    @Test fun shownOnceUnlessSnoozed() {
        assertNull(reminderAt(task("2026-10-24", reminded = 1L), zone, 9))
        assertEquals(12345L, reminderAt(task("2026-10-24", reminded = 1L, snoozed = 12345L), zone, 9))
    }

    @Test fun noDateNoReminder() = assertNull(reminderAt(task(null, "10:00"), zone, 9))

    @Test fun daylightSavingChangeKeepsWallClockTime() =
        // Around the end of summer time a 10:00 reminder is still at 10:00 on the wall clock.
        assertEquals(at(2026, 10, 25, 10, 0), reminderAt(task("2026-10-25", "10:00"), zone, 9))
}
