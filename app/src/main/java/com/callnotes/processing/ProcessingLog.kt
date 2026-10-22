package com.callnotes.processing

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.callnotes.util.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text log of processing runs (`files/transcription.log`, name kept from 0.3):
 * timings, memory, battery, model and speaker-separation decisions, errors — never
 * transcript text — so the owner can share it when something misbehaves. Every [step] is also stored as the current stage, so a
 * run that kills the process is reported on the next launch ([recoverIfDied]).
 */
object ProcessingLog {

    private const val MAX_BYTES = 64 * 1024

    fun file(context: Context) = File(context.filesDir, "transcription.log")

    @Synchronized
    fun step(context: Context, text: String) {
        Prefs(context).processingStage = text
        write(context, text)
    }

    /** The run ended normally; nothing to recover. */
    @Synchronized
    fun end(context: Context, text: String) {
        write(context, text)
        Prefs(context).processingStage = null
    }

    @Synchronized
    fun write(context: Context, text: String) {
        val f = file(context)
        runCatching {
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText("…\n" + keep.substringAfter('\n'))
            }
            f.appendText("${stamp()}  $text\n")
        }
    }

    /** Device and memory line written at the start of a run. */
    fun deviceLine(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val thermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "RAM ${gb(mem.totalMem)} GB, free ${gb(mem.availMem)} GB" +
            (if (mem.lowMemory) " (LOW)" else "") + ", thermal $thermal"
    }

    fun freeRam(context: Context): String {
        val mem = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mem)
        return "free RAM ${gb(mem.availMem)} GB"
    }

    /**
     * Called once per process start. A stage left over means the previous process died
     * in the middle of a run; returns that stage (and logs it), else null.
     */
    fun recoverIfDied(context: Context): String? {
        val prefs = Prefs(context)
        val stage = prefs.processingStage ?: return null
        write(context, "!! previous run stopped abruptly during: $stage (process killed — likely out of memory or a native crash)")
        prefs.processingStage = null
        return stage
    }

    private fun gb(bytes: Long) = "%.1f".format(Locale.US, bytes / 1_000_000_000.0)
    private fun stamp() = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
}
