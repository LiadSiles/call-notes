package com.callnotes.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.callnotes.CallNotesApp
import com.callnotes.data.MemoState
import com.callnotes.data.VoiceMemoEntity
import com.callnotes.processing.Phase
import com.callnotes.processing.ProcessingStatus
import com.callnotes.util.VoiceMemos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Whether the microphone may be used (the app's core permission; may have been revoked). */
internal fun micAllowed(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Records a voice note for a new task. Recording starts when the dialog opens; "סיום" (or
 * two minutes, or leaving the app) stops it and hands it on — Whisper, then Gemma, in the
 * background; the note shows in the task list meanwhile. "ביטול" throws it away.
 */
@Composable
fun VoiceTaskDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    var recording by remember { mutableStateOf<VoiceMemos.Recording?>(null) }
    var failed by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }

    /** Stops and keeps (or throws away) the note; runs on even if the screen goes away. */
    fun close(keep: Boolean) {
        if (closing) return
        closing = true
        val r = recording
        recording = null
        if (r != null) app.launchBackground {
            if (!keep) { VoiceMemos.discard(r); return@launchBackground }
            val message = when (VoiceMemos.finish(app, r)) {
                VoiceMemos.Outcome.TOO_SHORT -> "ההקלטה קצרה מדי — נסה שוב"
                VoiceMemos.Outcome.SILENT -> "לא נקלט קול — בדוק שהמיקרופון לא חסום"
                else -> null
            }
            if (message != null) withContext(Dispatchers.Main) { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        }
        onDismiss()
    }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { VoiceMemos.start(app) }
        if (r == null) { failed = true; return@LaunchedEffect }
        recording = r
        while (recording === r) {
            elapsed = r.elapsedMs
            level = (r.level / 12_000f).coerceIn(0f, 1f)
            if (elapsed >= VoiceMemos.MAX_MS) { close(keep = true); break }
            delay(100)
        }
    }
    // Leaving the app (home, a call coming in) ends the note: the mic is not kept in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { close(keep = true) }
    DisposableEffect(Unit) { onDispose { if (recording != null) close(keep = true) } }

    AlertDialog(
        onDismissRequest = {}, // only the buttons: a stray tap outside shouldn't lose the note
        title = { Text("הקלטת משימה") },
        text = {
            if (failed) {
                Text("לא ניתן להפעיל את המיקרופון — ייתכן שאפליקציה אחרת משתמשת בו.", textAlign = TextAlign.Center)
            } else {
                VoiceTaskBody(elapsed, level)
            }
        },
        confirmButton = {
            if (!failed) Button(onClick = { close(keep = true) }, enabled = recording != null) { Text("סיום") }
        },
        dismissButton = {
            TextButton(onClick = { if (failed) onDismiss() else close(keep = false) }) { Text(if (failed) "סגירה" else "ביטול") }
        },
    )
}

/** The recording dialog's body: hint, a microphone that grows with the voice, the time used. */
@Composable
internal fun VoiceTaskBody(elapsed: Long, level: Float) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "אמרו מה צריך לעשות ומתי, למשל: ״להתקשר לרופא מחר בעשר״.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val pulse by animateFloatAsState(1f + level * 0.35f, tween(120), label = "level")
        Box(Modifier.padding(vertical = 24.dp).size(120.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(96.dp).scale(pulse).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
            )
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Mic, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
                }
            }
        }
        Text(
            "${clock(elapsed)} / ${clock(VoiceMemos.MAX_MS)}",
            style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(
            progress = { elapsed.toFloat() / VoiceMemos.MAX_MS },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/**
 * A voice note on its way to becoming a task, at the top of the task list: what is
 * happening to it now, and delete (and retry, or write the task by hand, when it failed or
 * no task was found).
 */
@Composable
internal fun MemoCard(
    memo: VoiceMemoEntity,
    transcribing: ProcessingStatus.State,
    summarising: ProcessingStatus.State,
    onRetry: () -> Unit,
    onWrite: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val active = when (memo.state) {
        MemoState.RECORDED -> transcribing.takeIf { it.memoId == memo.id }
        MemoState.TRANSCRIBED -> summarising.takeIf { it.memoId == memo.id }
        else -> null
    }
    val (status, tone) = when (memo.state) {
        MemoState.RECORDED -> when (active?.phase) {
            null -> "ממתין לתמלול" to Tone.Neutral
            Phase.LOADING, Phase.PREPARING_MODEL -> "טוען את מודל התמלול…" to Tone.Busy
            else -> (if (active.progressMax > 0) "מתמלל · ${active.progress * 100 / active.progressMax}%" else "מתמלל…") to Tone.Busy
        }
        MemoState.TRANSCRIBED -> when (active?.phase) {
            null -> "ממתין למודל הסיכום" to Tone.Neutral
            Phase.LOADING_ANALYZER -> "טוען את מודל הסיכום…" to Tone.Busy
            Phase.WAITING_MEMORY -> "ממתין לזיכרון פנוי…" to Tone.Busy
            else -> "כותב את המשימה…" to Tone.Busy
        }
        MemoState.NO_TASK -> (memo.error ?: "לא נמצאה משימה בהקלטה") to Tone.Bad
        else -> (memo.error ?: "נכשל") to Tone.Bad
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Mic, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Text(
                    "הקלטה · ${formatShort(memo.createdAt)} · ${clock(memo.durationMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Pill(status, tone)
            }
            if (!memo.text.isNullOrBlank()) {
                Text(
                    "\"${memo.text}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 4,
                )
            }
            if (active != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
                val canRetry = memo.state == MemoState.FAILED && (memo.path != null || memo.text != null)
                if (canRetry) TextButton(onClick = onRetry) { Text("נסה שוב") }
                if (memo.state == MemoState.NO_TASK || memo.state == MemoState.FAILED && memo.text != null) {
                    TextButton(onClick = onWrite) { Text("כתיבת משימה") }
                }
                TextButton(onClick = onDelete) {
                    Text(if (active != null || memo.state == MemoState.RECORDED || memo.state == MemoState.TRANSCRIBED) "ביטול" else "מחיקה")
                }
            }
        }
    }
}
