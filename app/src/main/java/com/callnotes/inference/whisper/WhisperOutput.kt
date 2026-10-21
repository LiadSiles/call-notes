package com.callnotes.inference.whisper

import com.callnotes.inference.Transcript

/**
 * Turns the arrays returned by [WhisperNative.transcribe] into a [Transcript].
 * Times from whisper.cpp are in 10 ms units. Token text arrives as raw UTF-8 bytes
 * because a token can end in the middle of a Hebrew letter; bytes are joined per word
 * before decoding.
 */
object WhisperOutput {

    fun parse(raw: Array<Any>): Transcript {
        val segBytes = raw[0] as ByteArray
        val segOff = raw[1] as IntArray
        val segT0 = raw[2] as LongArray
        val segT1 = raw[3] as LongArray
        val tokBytes = raw[4] as ByteArray
        val tokOff = raw[5] as IntArray
        val tokT0 = raw[6] as LongArray
        val tokT1 = raw[7] as LongArray
        val tokDtw = raw[8] as LongArray
        val tokSeg = raw[9] as IntArray
        val tokP = raw[10] as FloatArray

        val segments = segT0.indices.mapNotNull { i ->
            val text = String(segBytes, segOff[i], segOff[i + 1] - segOff[i], Charsets.UTF_8).trim()
            if (text.isEmpty()) null else Transcript.Segment(segT0[i] * 10, segT1[i] * 10, text)
        }

        val words = ArrayList<Transcript.Word>()
        var wordBytes = java.io.ByteArrayOutputStream()
        var start = 0L; var end = 0L; var pSum = 0f; var pN = 0; var seg = -1
        fun flush() {
            val text = String(wordBytes.toByteArray(), Charsets.UTF_8).trim()
            if (text.isNotEmpty()) words += Transcript.Word(start, maxOf(end, start), text, if (pN > 0) pSum / pN else 0f)
            wordBytes = java.io.ByteArrayOutputStream(); pSum = 0f; pN = 0
        }
        for (t in tokT0.indices) {
            val from = tokOff[t]; val len = tokOff[t + 1] - from
            val startsWord = len > 0 && tokBytes[from] == ' '.code.toByte()
            if (wordBytes.size() == 0 || startsWord || tokSeg[t] != seg) {
                if (wordBytes.size() > 0) flush()
                // DTW timing is closer to when the word is spoken; fall back to token timing.
                start = (if (tokDtw[t] >= 0) tokDtw[t] else tokT0[t]) * 10
                seg = tokSeg[t]
            }
            wordBytes.write(tokBytes, from, len)
            end = tokT1[t] * 10
            pSum += tokP[t]; pN++
        }
        if (wordBytes.size() > 0) flush()
        return Transcript(segments, words)
    }
}
