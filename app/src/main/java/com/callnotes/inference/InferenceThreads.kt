package com.callnotes.inference

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Threads for background processing (Whisper, voice fingerprints, the workers' own steps;
 * not Gemma — see GemmaAnalyzer.load) at a lower
 * priority than the screen. The native engines start their worker threads from these, and
 * those inherit the priority, so scrolling stays smooth while a call is processed; with
 * nothing on screen to compete with, processing runs as fast as before. Nice +5, not
 * THREAD_PRIORITY_BACKGROUND (10), which Android may confine to the slow cores.
 */
object InferenceThreads {
    private const val NICE = 5
    private val count = AtomicInteger()

    /** Enough for both workers' blocking native calls plus their short database steps. */
    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(6) { r ->
        Thread({ Process.setThreadPriority(NICE); r.run() }, "processing-${count.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
