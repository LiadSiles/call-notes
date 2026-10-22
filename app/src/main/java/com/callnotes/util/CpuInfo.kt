package com.callnotes.util

import java.io.File

/** CPU facts the native inference engines need. */
object CpuInfo {
    /**
     * Threads for heavy native inference: the performance cores only. On big.LITTLE
     * phones adding the little cores makes ggml slower (threads wait for the slowest).
     */
    fun inferenceThreads(): Int {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        if (freqs.isEmpty()) return 4
        val slowest = freqs.min()
        val big = freqs.count { it > slowest }
        val n = if (big == 0) minOf(freqs.size, 4) else big
        return n.coerceIn(2, 8)
    }
}
