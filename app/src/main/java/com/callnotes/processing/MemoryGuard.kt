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
 * Most of that memory is not the app's own: it is pages of the files LiteRT-LM maps — the
 * model file (3.7 GB) and its working copy of the weights (2.3 GB on the owner's phone).
 * PSS counts every page of them that was touched and is still in RAM, and when the phone has
 * RAM to spare nothing pushes them out (0.22.x: the summaries that died started with 6 GB
 * free; the ones that finished, with 3–4 GB). So near the line the guard first hands the
 * pages of [releasable] back to the kernel (they are read again from storage if needed),
 * and only if that isn't enough stops the summary just before the line.
 *
 * Sampling is cheap: the resident size (/proc/self/statm) every half second; the exact
 * PSS only once the resident size is near the line.
 */
class MemoryGuard(private val releasable: String?) {

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
     * each release: PSS before, after, and what the kernel was told (bytes, or -errno).
     */
    suspend fun <T> watch(
        onRelease: (before: Long, after: Long, result: Long) -> Unit,
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
                    if (used > RELEASE_AT && releasable != null && System.currentTimeMillis() - lastRelease > 5_000) {
                        lastRelease = System.currentTimeMillis()
                        val result = NativeMemory.release(releasable)
                        val after = pss()
                        releases++
                        if (used - after > freedMost) freedMost = used - after
                        onRelease(used, after, result)
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
                delay(500)
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
        /** Past this PSS, hand the model file's pages back (at most every 5 s). */
        private const val RELEASE_AT = 4_800L * 1024 * 1024
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
