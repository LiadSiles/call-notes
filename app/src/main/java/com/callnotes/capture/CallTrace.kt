package com.callnotes.capture

import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timeline of one recording, for diagnosing gaps: device events (screen, audio mode,
 * route, capture-policy silencing, foreground service) and a per-second audio level.
 * Written next to the recording as `<name>.trace.txt`. Contains no audio and no words —
 * only timings and levels — so the user can share it safely.
 */
class CallTrace(private val startedAt: Long) {

    private val t0 = SystemClock.elapsedRealtime()
    private val events = ArrayList<String>()

    /** Per second of audio: RMS level and share of exactly-zero samples. */
    private val seconds = ArrayList<Pair<Double, Double>>()

    @Synchronized
    fun event(message: String) {
        events += "${clock(SystemClock.elapsedRealtime() - t0)}  $message"
    }

    @Synchronized
    fun second(rms: Double, zeroFraction: Double) {
        seconds += rms to zeroFraction
    }

    @Synchronized
    fun write(file: File) {
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sb.appendLine("CallNotes trace · ${fmt.format(Date(startedAt))}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}")
        sb.appendLine("Audio: ${seconds.size} s")
        sb.appendLine()
        sb.appendLine("== Gaps ==")
        val gaps = gaps()
        if (gaps.isEmpty()) sb.appendLine("none") else gaps.forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("== Events ==")
        events.forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("== Level per second (rms / zero%) ==")
        seconds.chunked(10).forEachIndexed { row, chunk ->
            sb.append(clock(row * 10_000L)).append("  ")
            sb.appendLine(chunk.joinToString(" ") { (r, z) -> "${r.toInt()}/${(z * 100).toInt()}" })
        }
        file.writeText(sb.toString())
    }

    /**
     * Runs of seconds that are digitally silent (≥90% exact zeros: the system fed us
     * silence) or acoustically very quiet for 3 s or more.
     */
    private fun gaps(): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < seconds.size) {
            val kind = kindOf(i)
            if (kind == null) { i++; continue }
            var j = i
            while (j + 1 < seconds.size && kindOf(j + 1) == kind) j++
            val len = j - i + 1
            if (kind == "ZERO" || len >= 3) {
                val label = if (kind == "ZERO") "digital silence (system muted capture)" else "very quiet"
                out += "${clock(i * 1000L)}–${clock((j + 1) * 1000L)}  ${len}s  $label"
            }
            i = j + 1
        }
        return out
    }

    private fun kindOf(i: Int): String? {
        val (rms, zeros) = seconds[i]
        return when {
            zeros >= 0.9 -> "ZERO"
            rms < 40 -> "QUIET"
            else -> null
        }
    }

    companion object {
        fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%02d:%02d".format(s / 60, s % 60)
        }

        /** The trace file that belongs to a recording. */
        fun fileFor(recording: File) = File(recording.parentFile, recording.nameWithoutExtension + ".trace.txt")
    }
}
