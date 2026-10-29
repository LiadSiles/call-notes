package com.callnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ImporterTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    private val now = LocalDate.of(2026, 10, 29).atTime(10, 45).atZone(zone).toInstant().toEpochMilli()
    private fun noon(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun whatsAppFilesGetAReadableName() {
        assertEquals("הודעה קולית מוואטסאפ", Importer.label("PTT-20261027-WA0003.opus"))
        assertEquals("הקלטה מוואטסאפ", Importer.label("AUD-20261027-WA0001.m4a"))
        assertEquals("הקלטה מוואטסאפ", Importer.label("WhatsApp Audio 2026-10-27 at 10.31.22.opus"))
        assertEquals("פגישה עם רואה החשבון", Importer.label("פגישה עם רואה החשבון.m4a"))
        assertEquals("קובץ אודיו", Importer.label(null))
    }

    @Test fun anEarlierRecordingCountsFromItsOwnDay() {
        // "מחר" in a message from Tuesday means Wednesday, not the day after it was shared.
        assertEquals(noon(2026, 10, 27), Importer.recordingDay("PTT-20261027-WA0003.opus", null, now, zone))
        assertEquals(noon(2026, 10, 20), Importer.recordingDay("recording.m4a", "20261020T081500.000Z", now, zone))
    }

    @Test fun todayOrUnknownIsNow() {
        assertEquals(now, Importer.recordingDay("PTT-20261029-WA0001.opus", null, now, zone))
        assertEquals(now, Importer.recordingDay("note.m4a", null, now, zone))
        assertEquals(now, Importer.recordingDay("PTT-20269999-WA0001.opus", null, now, zone)) // not a date
    }

    @Test fun extensionComesFromTheName() {
        assertEquals("opus", Importer.extension("PTT-20261027-WA0003.opus", "audio/ogg"))
        assertEquals("m4a", Importer.extension("a.b.m4a", null))
    }
}
