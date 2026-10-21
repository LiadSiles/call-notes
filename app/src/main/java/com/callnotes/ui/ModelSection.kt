package com.callnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.callnotes.inference.ModelManager
import com.callnotes.inference.ModelSpec
import com.callnotes.util.Prefs
import kotlinx.coroutines.delay

/**
 * One row per model. Tapping an installed model makes it active; tapping one that isn't
 * installed asks before downloading, and the current model stays active until the new
 * one has finished downloading and been verified.
 */
@Composable
fun ModelSection(models: ModelManager, prefs: Prefs, onReady: () -> Unit) {
    val snap by models.state.collectAsStateWithLifecycle()
    var askDownload by remember { mutableStateOf<ModelSpec?>(null) }
    var askDelete by remember { mutableStateOf<ModelSpec?>(null) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }

    // Poll the download while one runs; otherwise refresh occasionally.
    LaunchedEffect(Unit) {
        while (true) {
            models.refresh()
            delay(if (models.state.value.download is ModelManager.Download.Running) 1_000 else 5_000)
        }
    }
    LaunchedEffect(snap.active?.id) { if (snap.active != null) onReady() }

    val offered = models.offered()
    Panel {
        if (offered.isEmpty()) {
            Text("למכשיר יש פחות מ-5GB זיכרון, ולכן תמלול לא נתמך.", style = MaterialTheme.typography.bodyMedium)
            return@Panel
        }
        val recommended = models.recommended()
        offered.forEachIndexed { i, spec ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val installed = spec.id in snap.installed
            val active = snap.active?.id == spec.id
            val dl = snap.download
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (installed) models.select(spec) else askDownload = spec
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = active, onClick = {
                    if (installed) models.select(spec) else askDownload = spec
                })
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(spec.displayName, style = MaterialTheme.typography.titleSmall)
                        if (spec.id == recommended?.id) {
                            Spacer(Modifier.size(8.dp))
                            Pill("מומלץ למכשיר", Tone.Good)
                        }
                    }
                    val status = when {
                        dl is ModelManager.Download.Running && dl.spec.id == spec.id ->
                            "מוריד… ${gb(dl.bytes)} מתוך ${gb(spec.sizeBytes)}"
                        dl is ModelManager.Download.Verifying && dl.spec.id == spec.id -> "בודק את הקובץ…"
                        active -> "פעיל · ${gb(spec.sizeBytes)}"
                        installed -> "מותקן · ${gb(spec.sizeBytes)}"
                        else -> "לא מותקן · ${gb(spec.sizeBytes)}"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (dl is ModelManager.Download.Failed && dl.spec.id == spec.id) {
                        Text(dl.message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                when {
                    dl is ModelManager.Download.Running && dl.spec.id == spec.id ->
                        TextButton(onClick = { models.cancelDownload() }) { Text("ביטול") }
                    installed -> TextButton(onClick = { askDelete = spec }) { Text("מחיקה") }
                    else -> TextButton(onClick = { askDownload = spec }) { Text("הורדה") }
                }
            }
            if (dl is ModelManager.Download.Running && dl.spec.id == spec.id) {
                LinearProgressIndicator(
                    progress = { dl.bytes.toFloat() / spec.sizeBytes },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            } else if (dl is ModelManager.Download.Verifying && dl.spec.id == spec.id) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 6.dp))
            }
        }
        Text(
            "המודל רץ על הטלפון בלבד. ההקלטות והתמלולים לא יוצאים ממנו; הרשת משמשת רק להורדה.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    askDownload?.let { spec ->
        val switching = snap.active != null
        AlertDialog(
            onDismissRequest = { askDownload = null },
            title = { Text("להוריד את ${spec.displayName}?") },
            text = {
                Column {
                    Text(
                        "גודל ההורדה ${gb(spec.sizeBytes)}." +
                            if (switching) " המודל הנוכחי יישאר פעיל עד שההורדה תסתיים." else "",
                    )
                    if (!models.hasSpaceFor(spec)) {
                        Text("אין מספיק מקום פנוי בטלפון.", color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        androidx.compose.material3.Checkbox(checked = wifiOnly, onCheckedChange = {
                            wifiOnly = it; prefs.wifiOnly = it
                        })
                        Text("רק ב-Wi-Fi")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = models.hasSpaceFor(spec),
                    onClick = { models.startDownload(spec, wifiOnly); askDownload = null },
                ) { Text("הורדה") }
            },
            dismissButton = { TextButton(onClick = { askDownload = null }) { Text("ביטול") } },
        )
    }

    askDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text("למחוק את ${spec.displayName}?") },
            text = { Text("יתפנו ${gb(spec.sizeBytes)}. תמלולים קיימים נשארים. אפשר להוריד שוב בכל זמן.") },
            confirmButton = {
                TextButton(onClick = { models.delete(spec); askDelete = null }) {
                    Text("מחיקה", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("ביטול") } },
        )
    }
}
