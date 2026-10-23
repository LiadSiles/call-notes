package com.callnotes.processing

import android.os.PowerManager
import com.callnotes.CallNotesApp
import com.callnotes.data.CallEntity
import com.callnotes.util.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * One queue of background work (transcription, or summaries) inside [TranscriptionService].
 * The two workers run at the same time, each with its own model, status, notification
 * and cancel. Each call's work runs as a child job so cancelling one call doesn't stop
 * the worker; the native engine is told to abort first, so a cancel takes effect at once.
 */
abstract class Worker(protected val host: TranscriptionService, val notificationId: Int) {

    protected val app get() = host.application as CallNotesApp
    protected val dao get() = app.database.calls()

    /** The worker's loop, while it runs. */
    @Volatile var job: Job? = null
    /** The call being worked on now (including while its model loads). */
    @Volatile var callId: Long? = null
        protected set
    @Volatile private var item: Deferred<Boolean>? = null
    /** Calls the user cancelled while they were running here; their results are dropped. */
    val cancelled: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    val running get() = job?.isActive == true

    /** Whether this worker currently holds a model in memory. */
    @Volatile var modelLoaded = false
        protected set

    abstract suspend fun loop()

    /** Stop the native engine's current computation now (any thread). */
    protected abstract fun abortNative()

    /** Publishes this worker's progress for the UI. */
    protected abstract fun publish(state: ProcessingStatus.State)

    protected abstract val tag: String

    /** Main thread. The user cancelled [id]: abort it if it's running here. */
    fun cancelCall(id: Long) {
        if (callId != id) return
        cancelled += id
        abortNative()
        item?.cancel()
        publish(ProcessingStatus.State(running = true)) // the UI drops it at once
        host.showWork(this, "מבטל…", "")
    }

    /**
     * Main thread. Stop everything this worker is doing. The current call is not marked
     * cancelled: it goes back to the queue (the UI was already cleared by the caller).
     */
    fun stopNow() {
        if (!running) return
        abortNative()
        item?.cancel()
        job?.cancel()
    }

    /** Stops the current call's job without marking it cancelled (it returns to the queue). */
    protected fun cancelItem() { item?.cancel() }

    /**
     * Runs [block] for one call; null if it was cancelled (the worker carries on). The call
     * runs as its own job so cancelling one call doesn't stop the worker; if the worker
     * itself is stopped (stop all, battery saver, charger unplugged) the call is stopped too.
     */
    protected suspend fun runItem(block: suspend () -> Boolean): Boolean? {
        // Stopped while the model was loading: don't start the call at all.
        currentCoroutineContext().ensureActive()
        val work = host.scope.async { block() }
        item = work
        return try {
            work.await()
        } catch (e: CancellationException) {
            // The call was cancelled, or the worker was stopped while waiting for it: make
            // sure the call stops as well (it runs outside the worker's job).
            if (work.isActive) { abortNative(); work.cancel() }
            currentCoroutineContext().ensureActive() // the whole worker is stopping
            null
        } finally {
            // Never release a model while the native side is still inside it.
            withContext(NonCancellable) { work.join() }
            item = null
        }
    }

    protected fun begin(call: CallEntity) { callId = call.id }
    protected fun end() { callId = null }

    protected fun log(text: String) = ProcessingLog.write(host, "[$tag] $text")
    protected fun step(text: String) = ProcessingLog.step(host, "[$tag] $text")

    protected fun label(call: CallEntity) = Notifications.callLabel(call)

    protected suspend fun waitWhileHot() {
        val power = host.getSystemService(PowerManager::class.java)
        var logged = false
        while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            if (!logged) { step("waiting for the phone to cool down"); logged = true }
            publish(ProcessingStatus.State(running = true, phase = Phase.WAITING_THERMAL))
            host.showWork(this, "הטלפון חם", "ממתין שיתקרר")
            delay(60_000)
        }
    }

    protected fun secs(since: Long) = "%.1f s".format((System.currentTimeMillis() - since) / 1000.0)
}
