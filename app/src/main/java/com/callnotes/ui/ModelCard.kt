package com.callnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.inference.ModelManager
import com.callnotes.processing.ProcessingStatus
import com.callnotes.util.Prefs

/** Transcription card: model choice, auto-transcribe switch, live progress, last run. */
@Composable
fun ModelCard(models: ModelManager, prefs: Prefs, onReady: () -> Unit) {
    val processing by ProcessingStatus.state.collectAsStateWithLifecycle()
    val noteVersion by ProcessingStatus.version.collectAsStateWithLifecycle()
    val lastRun = remember(noteVersion) { prefs.lastProcessing }
    var autoTranscribe by remember { mutableStateOf(prefs.autoTranscribe) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("תמלול", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            ModelSection(models, prefs, onReady)

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("תמלול אוטומטי", style = MaterialTheme.typography.bodyLarge)
                    Text("כל הקלטה מתומללת ברקע בסיומה.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = autoTranscribe, onCheckedChange = { autoTranscribe = it; prefs.autoTranscribe = it })
            }

            if (processing.running) {
                val label = when (processing.phase) {
                    "loading" -> "טוען את המודל…"
                    "waiting_thermal" -> "הטלפון חם — ממתין שיתקרר"
                    else -> "מתמלל… מקטע ${processing.chunksDone}/${processing.chunksTotal}"
                }
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            lastRun?.let {
                Text("הרצה אחרונה: $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

// Small building blocks used by ModelSection (shared with the redesign branch).

enum class Tone { Neutral, Good, Busy, Bad }

@Composable
fun Panel(content: @Composable () -> Unit) {
    Column { content() }
}

@Composable
fun Pill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.Neutral -> cs.surfaceVariant to cs.onSurfaceVariant
        Tone.Good -> cs.primaryContainer to cs.onPrimaryContainer
        Tone.Busy -> cs.secondaryContainer to cs.onSecondaryContainer
        Tone.Bad -> cs.errorContainer to cs.onErrorContainer
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

fun gb(bytes: Long) = "%.1fGB".format(bytes / 1_000_000_000.0)
