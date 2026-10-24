package com.callnotes.inference.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperPromptTest {

    @Test fun namesThenWordsCommaSeparated() =
        assertEquals("ליעד, דני כהן, נתב״ג, הצעת מחיר.",
            WhisperPrompt.build("ליעד", "דני כהן", listOf("נתב״ג", " הצעת מחיר ", "ליעד")))

    @Test fun latinAndEmptyLeftOut() =
        assertEquals("ליעד.", WhisperPrompt.build("ליעד", "Dani Cohen", listOf("", "Zoom")))

    @Test fun nothingGivesEmpty() = assertEquals("", WhisperPrompt.build(null, null, emptyList()))

    @Test fun keptShort() {
        val p = WhisperPrompt.build(null, null, (1..100).map { "מילה$it" })
        assertTrue(p.length <= WhisperPrompt.MAX_CHARS)
        assertTrue(p.endsWith("."))
    }

    @Test fun settingsFieldSplits() =
        assertEquals(listOf("נתב״ג", "הצעת מחיר", "משה"), WhisperPrompt.parseList("נתב״ג, הצעת מחיר\nמשה,,"))
}
