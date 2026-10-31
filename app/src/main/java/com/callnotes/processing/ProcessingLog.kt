package com.callnotes.processing

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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
     * A run the process died in: the stage it was at, whether Android reports a native crash
     * or a stop for using too much memory, whether it was a cleanup the user (or a cleaner
     * app) started — not the work's fault — and the call being summarised, if any.
     */
    data class Death(val stage: String, val nativeCrash: Boolean, val memory: Boolean, val cleanup: Boolean = false) {
        val summaryCall: Long? get() = SUMMARY_STAGE.find(stage)?.groupValues?.get(1)?.toLongOrNull()
        /** Gemma was loading or working on the GPU. */
        val onGpu: Boolean get() = stage.startsWith("[summary]") && "on the GPU" in stage
    }

    /**
     * Android's description of an exit names a cleanup: HyperOS's "SwipeUpClean" (recent apps
     * cleared), "OneKeyClean" (the one-tap clean), or "… from process:com.miui.securitycenter"
     * (the Security app's cleanup).
     */
    fun isCleanup(description: String?): Boolean =
        description != null && listOf("SwipeUpClean", "OneKeyClean", "securitycenter").any { it in description }

    /** What [SummaryWorker] writes as its stage while it works on a call. */
    private val SUMMARY_STAGE = Regex("""^\[summary] call (\d+):""")

    /**
     * Called once per process start. A stage left over means the previous process died
     * in the middle of a run; returns it (and logs it with Android's exit reason), else null.
     */
    fun recoverIfDied(context: Context): Death? {
        val prefs = Prefs(context)
        val stage = prefs.processingStage ?: return null
        val exit = lastExit(context)
        val why = exit?.let { "${reasonName(it.reason)}${it.description?.let { d -> ": $d" } ?: ""}" }
            ?: "likely out of memory or a native crash"
        write(context, "!! previous run stopped abruptly during: $stage (process killed — $why)")
        prefs.processingStage = null
        // HyperOS: "[FORCE STOP] stop com.callnotes due to used too many pss resource …".
        val memory = exit != null && (exit.reason == REASON_LOW_MEMORY ||
            exit.description?.contains("pss", ignoreCase = true) == true)
        return Death(stage, exit?.reason == REASON_CRASH_NATIVE, memory, isCleanup(exit?.description))
    }

    /**
     * Once per process start: every way the app's process ended since the last start, as
     * Android recorded it (Android 11+) — a crash, a kill for memory, "stop" from settings…
     * When the process dies, Android does not bring the recording (accessibility) service
     * back by itself, so this is how to tell why it had to be turned on again.
     */
    fun logExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = Prefs(context)
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .filter { it.processName == context.packageName && it.timestamp > prefs.lastExitLogged }
        }.getOrNull() ?: return
        if (exits.isEmpty()) return
        prefs.lastExitLogged = exits.maxOf { it.timestamp }
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
        exits.sortedBy { it.timestamp }.forEach { e ->
            if (e.reason == ApplicationExitInfo.REASON_EXIT_SELF) return@forEach // closed normally
            write(context, "app process ended at ${time.format(Date(e.timestamp))}: ${reasonName(e.reason)}" +
                (e.description?.let { ": $it" } ?: "") +
                " · was ${importanceName(e.importance)} · memory ${e.pss / 1024} MB")
        }
    }

    private fun importanceName(importance: Int) = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "running a foreground service"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "in the background (service)"
        else -> "in the background (cached)"
    }

    /** A crash in the app's own code: what and where (class names stay readable in release builds). */
    fun crash(context: Context, thread: String, e: Throwable) {
        val frames = generateSequence(e) { it.cause }.take(3).joinToString(" | caused by ") { t ->
            "${t.javaClass.name}: ${t.message} @ " + t.stackTrace.take(8).joinToString(" < ") {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            }
        }
        write(context, "!! app crashed (thread $thread): $frames")
    }

    /** Android's record of how a process ended ([ApplicationExitInfo], read on Android 11+). */
    private class Exit(val reason: Int, val description: String?)

    /** How the previous process of this app ended (Android 11+; the description holds no call content). */
    private fun lastExit(context: Context): Exit? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                ?.let { Exit(it.reason, it.description) }
        }.getOrNull()
    }

    private fun reasonName(reason: Int) = when (reason) {
        REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_OTHER -> "killed by the system"
        else -> "exit reason $reason"
    }

    /** ApplicationExitInfo.REASON_CRASH_NATIVE (API 30). */
    private const val REASON_CRASH_NATIVE = 5
    /** ApplicationExitInfo.REASON_LOW_MEMORY (API 30). */
    private const val REASON_LOW_MEMORY = 3

    private fun gb(bytes: Long) = "%.1f".format(Locale.US, bytes / 1_000_000_000.0)
    private fun stamp() = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
}
