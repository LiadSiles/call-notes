package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpeakerDecoderTest {

    private val rnd = Random(3)
    private val a = FloatArray(16) { if (it < 8) 1f else 0f }
    private val b = FloatArray(16) { if (it < 8) 0f else 1f }
    private fun voice(base: FloatArray) = FloatArray(base.size) { base[it] + (rnd.nextFloat() - 0.5f) * 0.4f }

    /** Words of [plan] (speaker, count): 300 ms words, 80 ms apart; [pause] ms between turns. */
    private fun words(plan: List<Pair<Int, Int>>, pause: Long): Pair<List<Transcript.Word>, IntArray> {
        val out = ArrayList<Transcript.Word>()
        val who = ArrayList<Int>()
        var t = 0L
        for ((s, n) in plan) {
            repeat(n) { out += Transcript.Word(t, t + 300, "w"); who += s; t += 380 }
            t += pause - 80
        }
        return out to who.toIntArray()
    }

    private val plan = listOf(0 to 6, 1 to 4, 0 to 2, 1 to 6, 0 to 5, 1 to 3)

    @Test fun quickAnswersAreSplitByVoice() {
        // Answers with no pause at all (80 ms): grouping at pauses would merge the turns.
        val (ws, truth) = words(plan, pause = 80)
        val voices = truth.map { voice(if (it == 0) a else b) }
        val r = SpeakerDecoder.decode(ws, DoubleArray(ws.size) { -30.0 }, voices)!!
        val labels = if (r.labels[0] == 0) r.labels else r.labels.map { 1 - it }.toIntArray()
        assertArrayEquals(truth, labels)
        assertTrue(r.voiceSimilarity != null)
    }

    @Test fun loudnessAndVoiceTogether() {
        // A small level gap (6 dB) alone would be shaky; with the voices it is clear.
        val (ws, truth) = words(plan, pause = 400)
        val levels = DoubleArray(ws.size) { if (truth[it] == 0) -24.0 + (it % 3) else -30.0 - (it % 3) }
        val r = SpeakerDecoder.decode(ws, levels, truth.map { voice(if (it == 0) a else b) })!!
        assertArrayEquals(truth, r.labels) // 0 = the louder one
        assertTrue(r.level != null && r.voiceSimilarity != null)
    }

    @Test fun loudnessOverrulesAWeakVoiceMistake() {
        // Held to the ear: 20 dB apart; one word's fingerprint looks like the other voice.
        val (ws, truth) = words(plan, pause = 400)
        val levels = DoubleArray(ws.size) { if (truth[it] == 0) -20.0 else -40.0 }
        val voices = truth.mapIndexed { i, s -> voice(if ((s == 0) != (i == 3)) a else b) }
        assertArrayEquals(truth, SpeakerDecoder.decode(ws, levels, voices)!!.labels)
    }

    @Test fun oneVoiceAndEvenLevelsGiveNothing() {
        val (ws, _) = words(plan, pause = 400)
        assertNull(SpeakerDecoder.decode(ws, DoubleArray(ws.size) { -30.0 }, ws.map { voice(a) }))
    }

    @Test fun whoIsTheOwner() {
        val (ws, truth) = words(plan, pause = 400)
        val handset = SpeakerDecoder.decode(ws, DoubleArray(ws.size) { if (truth[it] == 0) -20.0 else -40.0 }, List(ws.size) { null })!!
        // Held to the ear: the louder one, even against the voice sample.
        assertEquals(0, SpeakerDecoder.whoIsMe(handset, speakerOn = false, voiceSays = 1, firstSpeaker = 0, incoming = true).first)
        // Speakerphone: loudness says nothing about who is who; the voice sample decides…
        assertEquals(1, SpeakerDecoder.whoIsMe(handset, speakerOn = true, voiceSays = 1, firstSpeaker = 0, incoming = true).first)
        // …or, without one, who answered: on an outgoing call the other side speaks first.
        assertEquals(1, SpeakerDecoder.whoIsMe(handset, speakerOn = true, voiceSays = null, firstSpeaker = 0, incoming = false).first)
        assertNull(SpeakerDecoder.whoIsMe(handset, speakerOn = true, voiceSays = null, firstSpeaker = 0, incoming = null).first)
    }

    @Test fun voiceTrackCoversSpeechAndGivesEveryWordAVoice() {
        val (ws, truth) = words(plan, pause = 2_000)
        val track = VoiceTrack.windows(ws)
        assertTrue(track.all { it.last - it.first == VoiceTrack.WINDOW_MS })
        // Each window's voice: whoever has most words in it.
        val prints = track.map { r ->
            val inside = ws.indices.filter { ws[it].startMs < r.last && ws[it].endMs > r.first }
            if (inside.isEmpty()) null else voice(if (inside.count { truth[it] == 0 } * 2 >= inside.size) a else b)
        }
        val perWord = VoiceTrack.perWord(ws, track, prints)
        assertTrue(perWord.all { it != null })
        val right = perWord.indices.count { i -> Voice.cosine(perWord[i]!!, if (truth[i] == 0) a else b) > 0.5f }
        assertTrue("$right of ${ws.size}", right >= ws.size * 0.85)
    }
}
