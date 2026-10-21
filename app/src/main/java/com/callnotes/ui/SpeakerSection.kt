package com.callnotes.ui

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.CallNotesApp
import com.callnotes.inference.ModelCatalog
import com.callnotes.inference.ModelManager
import com.callnotes.inference.speakers.SpeakerDiarizer
import com.callnotes.inference.speakers.VoiceProfile
import com.callnotes.util.CpuInfo
import com.callnotes.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * "Who said what": the speaker-separation models (one small pack) and the owner's voice
 * sample, which lets the app label the owner as "me" and the other side by name.
 */
@Composable
fun SpeakerSection(prefs: Prefs) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val models = app.models
    val snap by models.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pack = ModelCatalog.SPEAKER_PACK
    val installed = pack.all { it.id in snap.installed }
    val dl = snap.download
    val downloading = (dl as? ModelManager.Download.Running)?.takeIf { d -> pack.any { it.id == d.spec.id } }
    val verifying = (dl as? ModelManager.Download.Verifying)?.takeIf { d -> pack.any { it.id == d.spec.id } }
    val failed = (dl as? ModelManager.Download.Failed)?.takeIf { d -> pack.any { it.id == d.spec.id } }
    val packBytes = pack.sumOf { it.sizeBytes }
    var hasSample by remember { mutableStateOf(VoiceProfile.exists(context)) }
    var recording by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(downloading != null) {
        while (downloading != null) { models.refresh(); delay(1_000) }
    }

    Panel {
        Column {
            Text("הפרדת דוברים", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    downloading != null -> "מוריד… ${gb(pack.filter { it.id in snap.installed }.sumOf { it.sizeBytes } + downloading.bytes)} מתוך ${gb(packBytes)}"
                    verifying != null -> "בודק את הקבצים…"
                    installed -> "מותקן · ${gb(packBytes)} · משמש לשיחות ברמקול ובאינטרנט"
                    else -> "שני מודלים קטנים (${gb(packBytes)}) שמזהים דוברים לפי הקול — נחוצים לשיחות ברמקול ובאינטרנט. בשיחה צמודה לאוזן ההפרדה נעשית לפי עוצמת הקול גם בלעדיהם."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            failed?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (downloading != null || verifying != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
            Row {
                when {
                    downloading != null -> TextButton(onClick = { models.cancelDownload() }) { Text("ביטול") }
                    installed -> TextButton(onClick = { pack.forEach { models.delete(it) } }) { Text("מחיקה") }
                    verifying == null -> FilledTonalButton(onClick = { models.startDownloads(pack, prefs.wifiOnly) },
                        modifier = Modifier.padding(top = 6.dp)) { Text("הורדה") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            Text("דגימת הקול שלך", style = MaterialTheme.typography.titleSmall)
            Text(
                if (hasSample) "שמורה בטלפון בלבד. לפיה האפליקציה יודעת מי מהדוברים הוא את/ה בשיחות ברמקול ובאינטרנט."
                else "הקלטה חד-פעמית של כ-15 שניות מהקול שלך, כדי לסמן \"אני\" ואת שם הצד השני.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                FilledTonalButton(onClick = { recording = true }, enabled = installed) {
                    Text(if (hasSample) "הקלטה מחדש" else "הקלטת דגימה")
                }
                if (hasSample) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { askDelete = true }) { Text("מחיקה") }
                }
            }
            if (!installed) {
                Text("זמין אחרי הורדת מודלי הפרדת הדוברים.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (recording) VoiceSampleDialog(
        onDone = { samples ->
            recording = false
            scope.launch {
                result = "מעבד את הדגימה…"
                result = withContext(Dispatchers.IO) {
                    try {
                        val e = SpeakerDiarizer.embedOnly(models.fileFor(ModelCatalog.WESPEAKER_RESNET34), samples,
                            CpuInfo.inferenceThreads())
                        VoiceProfile.save(context, e)
                        val n = VoiceProfile.relabelAll(context)
                        "הדגימה נשמרה." + if (n > 0) " סומנו $n שיחות קיימות." else ""
                    } catch (e: Exception) {
                        "לא הצלחתי לעבד את הדגימה (${e.javaClass.simpleName}). נסה שוב."
                    }
                }
                hasSample = VoiceProfile.exists(context)
            }
        },
        onDismiss = { recording = false },
    )

    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("למחוק את דגימת הקול?") },
        text = { Text("השיחות יוצגו שוב כ\"דובר 1\" ו\"דובר 2\". אפשר להקליט דגימה חדשה בכל זמן.") },
        confirmButton = {
            TextButton(onClick = {
                askDelete = false
                VoiceProfile.delete(context)
                hasSample = false
                result = null
                scope.launch(Dispatchers.IO) { VoiceProfile.relabelAll(context) }
            }) { Text("מחיקה", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("ביטול") } },
    )
}

private const val SAMPLE_RATE = 16_000
private const val MAX_SECONDS = 20
private const val MIN_SECONDS = 8

/** Records the owner's voice from the phone microphone; returns 16 kHz mono floats. */
@SuppressLint("MissingPermission") // RECORD_AUDIO is a required setup permission
@Composable
private fun VoiceSampleDialog(onDone: (FloatArray) -> Unit, onDismiss: () -> Unit) {
    var seconds by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val samples = withContext(Dispatchers.IO) {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE))
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); return@withContext null }
            val out = ShortArray(SAMPLE_RATE * MAX_SECONDS)
            var n = 0
            val buf = ShortArray(SAMPLE_RATE / 10)
            rec.startRecording()
            try {
                while (isActive && n < out.size && !stopRequested) {
                    val r = rec.read(buf, 0, minOf(buf.size, out.size - n))
                    if (r <= 0) break
                    buf.copyInto(out, n, 0, r)
                    n += r
                    var sum = 0.0
                    for (i in 0 until r) sum += buf[i].toDouble() * buf[i]
                    level = (sqrt(sum / r) / 6_000).toFloat().coerceIn(0f, 1f)
                    seconds = n / SAMPLE_RATE.toFloat()
                }
            } finally {
                rec.stop(); rec.release()
            }
            FloatArray(n) { out[it] / 32768f }
        }
        running = false
        when {
            samples == null -> error = "לא ניתן להפעיל את המיקרופון."
            samples.size < SAMPLE_RATE * MIN_SECONDS -> { error = "קצר מדי — דבר/י לפחות $MIN_SECONDS שניות."; stopRequested = false }
            else -> onDone(samples)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("דגימת קול") },
        text = {
            Column {
                Text("דבר/י באופן טבעי, בקול רגיל, כ-15 שניות — למשל ספר/י מה עשית היום. " +
                    "ההקלטה נשארת בטלפון ומשמשת רק לזיהוי הקול שלך.")
                if (running) {
                    Text("מקליט… ${seconds.toInt()} / $MAX_SECONDS שניות", modifier = Modifier.padding(top = 12.dp))
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (running) TextButton(onClick = { stopRequested = true }, enabled = seconds >= MIN_SECONDS) { Text("סיום") }
            else TextButton(onClick = { error = null; seconds = 0f; stopRequested = false; running = true }) { Text("התחלה") }
        },
        dismissButton = { if (!running) TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}
