package com.callnotes.processing

import android.os.Debug
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps Gemma's work under the memory line past which the phone stops the whole app.
 *
 * HyperOS force-stops an app whose memory (PSS) passes 6 GB — the owner's log (0.22.1):
 * "[FORCE STOP] stop com.callnotes due to used too many pss resource, pss used: 8524200kb,
 * pss threshold: 6291456kb". A force stop also takes the app off the enabled accessibility
 * services, so recording stops until the user turns it on again. The guard stops the
 * summary just before that line instead.
 *
 * What it can't do (tried in 0.23.0–0.23.2, see decisions.md): free memory under it. The
 * memory is the model's own buffers, not file pages; moving them to swap doesn't help
 * (Android's PSS counts swapped pages too) and filled the phone's swap until Android closed
 * the app for low memory.
 *
 * Sampling is cheap: the resident size (/proc/self/statm) every half second; the exact
 * PSS only once the resident size is near the line.
 */
class MemoryGuard(
    /** Stop above this PSS ([LIMIT]; [REBUILD_LIMIT] while LiteRT-LM rebuilds its working files). */
    private val limit: Long = LIMIT,
) {

    /** Highest PSS seen (bytes; resident size until it got near the line). */
    @Volatile var peak = 0L
        private set

    /**
     * Runs [block]; if memory passes the limit it calls [onOver] (stop the native work),
     * cancels [block] and throws [TooMuchMemory].
     */
    suspend fun <T> watch(onOver: (Long) -> Unit, block: suspend () -> T): T = coroutineScope {
        val over = AtomicLong()
        val work = async { block() }
        val watcher = launch {
            while (true) {
                val used = resident().let { if (it > NEAR) pss() else it }
                if (used > peak) peak = used
                if (used > limit) {
                    over.set(used)
                    onOver(used)
                    work.cancel()
                    break
                }
                delay(if (used > NEAR) 250 else 500)
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            if (over.get() > 0) throw TooMuchMemory(over.get())
            throw e
        } finally {
            watcher.cancel()
        }
    }

    /** For the log. */
    fun summary(): String = "peak memory ${gb(peak)} GB"

    class TooMuchMemory(val bytes: Long) : Exception("memory ${gb(bytes)} GB, over the line")

    companion object {
        /** The phone's line is 6 GiB of PSS; stop a little before it (sampled every 0.5 s). */
        const val LIMIT = 5_900L * 1024 * 1024
        /**
         * While the engine rebuilds its working files (none at load) it needs more for that one
         * run; the files are only kept if it finishes, so allow it up to just under the line.
         */
        const val REBUILD_LIMIT = 6_060L * 1024 * 1024
        /** Above this resident size, measure the PSS itself. */
        private const val NEAR = 4_600L * 1024 * 1024

        private val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)

        /** Resident memory of this process (bytes). */
        fun resident(): Long = runCatching {
            File("/proc/self/statm").readText().trim().split(' ')[1].toLong() * pageSize
        }.getOrDefault(0L)

        /** PSS of this process (bytes) — slower to measure than [resident]. */
        fun pss(): Long = Debug.getPss() * 1024

        fun gb(bytes: Long) = "%.1f".format(Locale.US, bytes / (1024.0 * 1024 * 1024))

        fun mb(bytes: Long) = (bytes / (1024 * 1024)).toString()

        private val MAPPING = Regex("""^[0-9a-f]+-[0-9a-f]+ """)

        /**
         * Where the resident memory is (/proc/self/smaps), for the log: the model file
         * [modelName], LiteRT-LM's working copy of it (files named after it), other mapped
         * files, and the rest (the app's own memory, the model's working buffers).
         */
        fun breakdown(modelName: String?): String = runCatching {
            val rss = LongArray(4) // model, working copy, other files, the rest
            var kind = 3
            File("/proc/self/smaps").forEachLine { line ->
                if (MAPPING.containsMatchIn(line)) {
                    val path = line.indexOf('/').let { if (it < 0) "" else line.substring(it).trim() }
                    val name = path.substringAfterLast('/')
                    kind = when {
                        path.isEmpty() -> 3
                        modelName != null && name == modelName -> 0
                        modelName != null && name.startsWith(modelName) -> 1
                        else -> 2
                    }
                } else if (line.startsWith("Rss:")) {
                    rss[kind] += line.removePrefix("Rss:").trim().removeSuffix("kB").trim().toLong() * 1024
                }
            }
            "model file ${gb(rss[0])} GB · working copy ${gb(rss[1])} GB · other files ${gb(rss[2])} GB · the rest ${gb(rss[3])} GB"
        }.getOrDefault("not measured")
    }
}
