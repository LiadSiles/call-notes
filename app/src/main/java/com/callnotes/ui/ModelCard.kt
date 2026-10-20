package com.callnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.inference.ModelCatalog
import com.callnotes.inference.ModelManager
import com.callnotes.processing.ProcessingStatus
import com.callnotes.util.Prefs
import kotlinx.coroutines.delay

/** Model status, download, and the transcription switch. */
@Composable
fun ModelCard(models: ModelManager, prefs: Prefs, onReady: () -> Unit) {
    val state by models.state.collectAsStateWithLifecycle()
    val processing by ProcessingStatus.state.collectAsStateWithLifecycle()
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var autoTranscribe by remember { mutableStateOf(prefs.autoTranscribe) }
    val spec = models.selected()

    // Poll DownloadManager while a download or verification is in progress.
    LaunchedEffect(Unit) {
        while (true) {
            models.refresh()
            delay(if (models.state.value is ModelManager.State.Downloading) 1_000 else 5_000)
        }
    }
    LaunchedEffect(state) { if (state is ModelManager.State.Ready) onReady() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("תמלול", style = MaterialTheme.typography.titleMedium)

            if (spec == null) {
                Text("במכשיר יש פחות מ-5GB זיכרון — התמלול לא נתמך.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            Text(
                "מודל: ${spec.displayName} · ${gb(spec.sizeBytes)} · זיכרון במכשיר ${"%.1f".format(models.totalRamGb)}GB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                is ModelManager.State.NotDownloaded, is ModelManager.State.Failed -> {
                    if (s is ModelManager.State.Failed) {
                        Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "המודל רץ על המכשיר בלבד — ההקלטות והתמלולים לא יוצאים ממנו. ההורדה פעם אחת.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("רק ב-Wi-Fi", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it; prefs.wifiOnly = it })
                    }
                    if (!models.hasSpaceFor(spec)) {
                        Text("אין מספיק מקום פנוי (צריך ${gb(spec.sizeBytes + 500L * 1024 * 1024)}).",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else {
                        FilledTonalButton(onClick = { models.startDownload(spec, wifiOnly) }) {
                            Text("הורדת המודל (${gb(spec.sizeBytes)})")
                        }
                    }
                }
                is ModelManager.State.Downloading -> {
                    val fraction = if (s.total > 0) s.bytes.toFloat() / s.total else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("מוריד… ${gb(s.bytes)} מתוך ${gb(s.total)}", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { models.cancelDownload(); models.refresh() }) { Text("ביטול") }
                    }
                    if (wifiOnly && s.bytes == 0L) {
                        Text("ממתין ל-Wi-Fi…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is ModelManager.State.Verifying -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("בודק את תקינות הקובץ…", style = MaterialTheme.typography.bodySmall)
                }
                is ModelManager.State.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("תמלול אוטומטי", style = MaterialTheme.typography.bodyLarge)
                            Text("כל הקלטה מתומללת ברקע בסיום השיחה.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = autoTranscribe,
                            onCheckedChange = { autoTranscribe = it; prefs.autoTranscribe = it })
                    }
                    if (processing.running) {
                        val label = when (processing.phase) {
                            "loading" -> "טוען את המודל…"
                            "waiting_thermal" -> "הטלפון חם — ממתין שיתקרר"
                            else -> "מתמלל… מקטע ${processing.chunksDone}/${processing.chunksTotal}"
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                    val other = ModelCatalog.all.firstOrNull { it.id != spec.id && models.totalRamGb >= it.minRamGb }
                    Row {
                        TextButton(onClick = { models.delete(spec) }) { Text("מחיקת המודל") }
                        if (other != null) {
                            TextButton(onClick = { models.select(other) }) { Text("מעבר ל-${other.displayName}") }
                        }
                    }
                }
                ModelManager.State.Unsupported -> Unit
            }
        }
    }
}

private fun gb(bytes: Long) = "%.1fGB".format(bytes / 1_000_000_000.0)
