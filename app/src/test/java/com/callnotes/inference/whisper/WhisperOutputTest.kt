package com.callnotes.inference.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperOutputTest {

    /** Builds the native result layout from token strings split at arbitrary byte points. */
    private fun raw(segments: List<Pair<String, LongArray>>, tokens: List<Triple<ByteArray, Int, LongArray>>): Array<Any> {
        val segBytes = segments.joinToString("") { it.first }.toByteArray()
        val segOff = IntArray(segments.size + 1).also { o ->
            var acc = 0; segments.forEachIndexed { i, s -> acc += s.first.toByteArray().size; o[i + 1] = acc }
        }
        val tokBytes = tokens.fold(ByteArray(0)) { a, t -> a + t.first }
        val tokOff = IntArray(tokens.size + 1).also { o ->
            var acc = 0; tokens.forEachIndexed { i, t -> acc += t.first.size; o[i + 1] = acc }
        }
        return arrayOf(
            segBytes, segOff,
            LongArray(segments.size) { segments[it].second[0] }, LongArray(segments.size) { segments[it].second[1] },
            tokBytes, tokOff,
            LongArray(tokens.size) { tokens[it].third[0] }, LongArray(tokens.size) { tokens[it].third[1] },
            LongArray(tokens.size) { tokens[it].third[2] }, IntArray(tokens.size) { tokens[it].second },
            FloatArray(tokens.size) { 0.9f },
        )
    }

    @Test fun hebrewLetterSplitAcrossTokensIsRejoined() {
        val shalom = " שלום".toByteArray()          // space + 4 letters × 2 bytes
        val ma = " מה".toByteArray()
        val tokens = listOf(
            Triple(shalom.copyOfRange(0, 4), 0, longArrayOf(0, 20, -1)),   // ends mid-letter
            Triple(shalom.copyOfRange(4, shalom.size), 0, longArrayOf(20, 50, -1)),
            Triple(ma, 0, longArrayOf(60, 90, 55)),
        )
        val t = WhisperOutput.parse(raw(listOf(" שלום מה" to longArrayOf(0, 100)), tokens))
        assertEquals(listOf("שלום", "מה"), t.words.map { it.text })
        assertEquals(0L, t.words[0].startMs)
        assertEquals(500L, t.words[0].endMs)
        assertEquals(550L, t.words[1].startMs)  // DTW time preferred when present
        assertEquals("שלום מה", t.segments.single().text)
        assertEquals(1000L, t.segments.single().endMs)
    }

    @Test fun newSegmentStartsNewWordAndEmptySegmentsDrop() {
        val tokens = listOf(
            Triple("כן".toByteArray(), 0, longArrayOf(0, 30, -1)),
            Triple("לא".toByteArray(), 1, longArrayOf(40, 70, -1)),
        )
        val t = WhisperOutput.parse(raw(listOf("כן" to longArrayOf(0, 30), " " to longArrayOf(30, 35), "לא" to longArrayOf(40, 70)), tokens))
        assertEquals(listOf("כן", "לא"), t.words.map { it.text })
        assertEquals(listOf("כן", "לא"), t.segments.map { it.text })
    }
}
