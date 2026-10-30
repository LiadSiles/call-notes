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
 * services, so recording stops until the user turns it on again.
 *
 * Where it is (0.23.0 log): almost all in the model's own buffers — 5.5–6.1 GB within seconds
 * of the first answer, with the model file and LiteRT-LM's working copy barely resident. Under
 * memory pressure the phone compresses rarely used pages into its swap, which PSS doesn't
 * count — likely why summaries used to finish when the phone had 2–3 GB free and fail with
 * 6 GB free. Near the line the guard does that itself: it hands the model file's pages back
 * and moves the model's large buffers to swap (MADV_PAGEOUT); pages in use come back when
 * touched. Only if memory still passes the line is the summary stopped.
 *
 * Sampling is cheap: the resident size (/proc/self/statm) every half second; the exact
 * PSS only once the resident size is near the line.
 */
class MemoryGuard(private val modelFile: String?) {

    /** Highest PSS seen (bytes; resident size until it got near the line). */
    @Volatile var peak = 0L
        private set

    /** Times pages were handed back, and the most memory that freed at once. */
    @Volatile var releases = 0
        private set
    @Volatile var freedMost = 0L
        private set

    private var lastRelease = 0L

    /**
     * Runs [block]; if memory passes [LIMIT] even after releasing pages, it calls [onOver]
     * (stop the native work), cancels [block] and throws [TooMuchMemory]. [onRelease] reports
     * each release: PSS before, after, and the bytes of model file and of buffers the kernel
     * was told about (negative: an errno).
     */
    suspend fun <T> watch(
        onRelease: (before: Long, after: Long, file: Long, buffers: Long) -> Unit,
        onOver: (Long) -> Unit,
        block: suspend () -> T,
    ): T = coroutineScope {
        val over = AtomicLong()
        val work = async { block() }
        val watcher = launch {
            while (true) {
                var used = resident()
                if (used > NEAR) {
                    used = pss()
                    if (used > RELEASE_AT && System.currentTimeMillis() - lastRelease > 2_000) {
                        val file = modelFile?.let { NativeMemory.release(it) } ?: 0L
                        val buffers = NativeMemory.releaseAnonymous(BUFFER_MIN)
                        lastRelease = System.currentTimeMillis()
                        val after = pss()
                        releases++
                        if (used - after > freedMost) freedMost = used - after
                        onRelease(used, after, file, buffers)
                        used = after
                    }
                }
                if (used > peak) peak = used
                if (used > LIMIT) {
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

    /** For the log: "peak memory 5.1 GB · released pages 3× (up to 2.4 GB)". */
    fun summary(): String = "peak memory ${gb(peak)} GB" +
        if (releases > 0) " · released pages $releases× (up to ${gb(freedMost)} GB)" else ""

    class TooMuchMemory(val bytes: Long) : Exception("memory ${gb(bytes)} GB, over the ${gb(LIMIT)} GB line")

    companion object {
        /** The phone's line is 6 GiB of PSS; stop a little before it (sampled every 0.5 s). */
        const val LIMIT = 5_900L * 1024 * 1024
        /** Past this PSS, release pages (at most every 2 s; memory can jump 2 GB in a second). */
        private const val RELEASE_AT = 4_800L * 1024 * 1024
        /** Above this resident size, measure the PSS itself. */
        private const val NEAR = 4_600L * 1024 * 1024

        /** Buffers at least this big are the model's (the app's own allocations are far smaller). */
        private const val BUFFER_MIN = 32L * 1024 * 1024

        private val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)

        /** The phone's swap, for the log: "swap 3.1 of 6.0 GB free" (or "no swap"). */
        fun swap(): String = runCatching {
            val m = File("/proc/meminfo").readLines().associate { l ->
                l.substringBefore(':') to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L) * 1024
            }
            val total = m["SwapTotal"] ?: 0L
            if (total == 0L) "no swap" else "swap ${gb(m["SwapFree"] ?: 0L)} of ${gb(total)} GB free"
        }.getOrDefault("swap not measured")

        /** Resident memory of this process (bytes). */
        fun resident(): Long = runCatching {
            File("/proc/self/statm").readText().trim().split(' ')[1].toLong() * pageSize
        }.getOrDefault(0L)

        /** PSS of this process (bytes) — slower to measure than [resident]. */
        fun pss(): Long = Debug.getPss() * 1024

        fun gb(bytes: Long) = "%.1f".format(Locale.US, bytes / (1024.0 * 1024 * 1024))

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
