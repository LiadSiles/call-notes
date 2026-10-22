package com.callnotes.ui

import android.os.SystemClock
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.callnotes.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Optional lock: with it on, the app asks for fingerprint / face / the phone's PIN when it
 * opens or comes back after [GRACE_MS] in the background, its window is hidden from
 * screenshots and the recent-apps preview, and its notifications hide their text on the
 * lock screen. Recording and processing keep running while the app is locked.
 */
object AppLock {

    private const val GRACE_MS = 30_000L
    const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** True while the content must be hidden. */
    val locked = MutableStateFlow(false)
    private var backgroundSince = 0L
    private var started = false

    /** The phone has a screen lock or biometric we can ask for. */
    fun available(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun onStart(activity: FragmentActivity) {
        val on = Prefs(activity).appLock
        secureWindow(activity, on)
        if (!on) { locked.value = false; started = true; return }
        val away = SystemClock.elapsedRealtime() - backgroundSince
        if (!started || away > GRACE_MS) locked.value = true
        started = true
    }

    fun onStop() { backgroundSince = SystemClock.elapsedRealtime() }

    fun secureWindow(activity: FragmentActivity, on: Boolean) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Shows the system prompt; [onResult] gets true on success. */
    fun authenticate(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        )
    }

    fun unlock(activity: FragmentActivity) =
        authenticate(activity, "פתיחת CallNotes") { ok -> if (ok) locked.value = false }
}

/** What's shown instead of the app while it's locked. */
@Composable
fun LockScreen(activity: FragmentActivity) {
    LaunchedEffect(Unit) { AppLock.unlock(activity) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("CallNotes נעולה", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            Text(
                "ההקלטה והעיבוד ממשיכים כרגיל ברקע.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
            FilledTonalButton(onClick = { AppLock.unlock(activity) }) { Text("פתיחה") }
        }
    }
}
