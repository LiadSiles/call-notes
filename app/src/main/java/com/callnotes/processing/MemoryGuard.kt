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
 * pss threshold: 6291456kb", during a summary, again on every start. A force stop also
 * takes the app off the enabled accessibility services, so recording stops until the user
 * turns it on again. Stopping the summary just before that line keeps the app alive.
 *
 * Sampling is cheap: the resident size (/proc/self/statm) every half second; the exact
 * PSS (what the line is measured in) only once the resident size is near it.
 */
class MemoryGuard {

    /** Highest PSS seen (bytes; resident size until it got near the line). */
    @Volatile var peak = 0L
        private set

    /**
     * Runs [block]; if memory passes [LIMIT] it calls [onOver] (stop the native work), cancels
     * [block] and throws [TooMuchMemory].
     */
    suspend fun <T> watch(onOver: (Long) -> Unit, block: suspend () -> T): T = coroutineScope {
        val over = AtomicLong()
        val work = async { block() }
        val watcher = launch {
            while (true) {
                val used = resident().let { if (it > NEAR) pss() else it }
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

    class TooMuchMemory(val bytes: Long) : Exception("memory ${gb(bytes)} GB, over the ${gb(LIMIT)} GB line")

    companion object {
        /** The phone's line is 6 GiB of PSS; stop a little before it (sampled every 0.5 s). */
        const val LIMIT = 5_900L * 1024 * 1024
        /** Above this resident size, measure the PSS itself. */
        private const val NEAR = 5_300L * 1024 * 1024

        private val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)

        /** Resident memory of this process (bytes). */
        fun resident(): Long = runCatching {
            File("/proc/self/statm").readText().trim().split(' ')[1].toLong() * pageSize
        }.getOrDefault(0L)

        /** PSS of this process (bytes) — slower to measure than [resident]. */
        fun pss(): Long = Debug.getPss() * 1024

        fun gb(bytes: Long) = "%.1f".format(Locale.US, bytes / (1024.0 * 1024 * 1024))
    }
}
