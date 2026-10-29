package com.callnotes.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live state of the recording service, for the UI.
 *
 * The service runs in its own process (`:recorder`, see [PendingCalls]), so each process
 * has its own copy of this object. The recording process [publish]es changes as an
 * app-internal broadcast; the main process [listen]s, and on every return to the screen
 * [refresh]es: whether Android really has the service running (it is "on" in settings
 * but not running after its process died), and asks it for its state — which also makes
 * it start call detection if a permission was granted meanwhile.
 */
object ServiceStatus {

    data class State(
        val connected: Boolean = false,
        /** Call detection is running (needs the phone-state permission). */
        val monitoring: Boolean = false,
        val recordingNow: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Atomic read-modify-write (called from the main thread and from IO). */
    internal fun update(transform: (State) -> State) = _state.update(transform)

    /** Recording process → main process: the current state. */
    private const val ACTION_STATE = "com.callnotes.action.RECORDER_STATE"
    /** Main process → recording process: send your state (and re-check call detection). */
    const val ACTION_ASK = "com.callnotes.action.RECORDER_ASK"

    /** Recording process: tells the main process (and its screens) the state now. */
    fun publish(context: Context) {
        val s = _state.value
        context.sendBroadcast(
            Intent(ACTION_STATE).setPackage(context.packageName)
                .putExtra("connected", s.connected)
                .putExtra("monitoring", s.monitoring)
                .putExtra("recording", s.recordingNow)
        )
    }

    /** Main process, once: follow the recording process's state. */
    fun listen(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                _state.value = State(
                    connected = intent.getBooleanExtra("connected", false),
                    monitoring = intent.getBooleanExtra("monitoring", false),
                    recordingNow = intent.getBooleanExtra("recording", false),
                )
            }
        }
        // Not exported: only this app (its other process) can send it.
        ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter(ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh(context)
    }

    /** Main process (start, and each return to the screen). */
    fun refresh(context: Context) {
        if (!running(context)) {
            _state.value = State()
            return
        }
        _state.update { it.copy(connected = true) }
        context.sendBroadcast(Intent(ACTION_ASK).setPackage(context.packageName))
    }

    /**
     * Whether Android has the service bound and running now — not just switched on in
     * settings (a service whose process died stays "on" there but is not bound again).
     */
    fun running(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val si = it.resolveInfo?.serviceInfo
            si?.packageName == context.packageName && si.name == CallRecorderService::class.java.name
        }
    }
}
