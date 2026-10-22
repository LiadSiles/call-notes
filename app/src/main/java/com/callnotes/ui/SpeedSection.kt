package com.callnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.inference.InferenceEngine
import com.callnotes.inference.ModelManager
import com.callnotes.inference.ModelRole
import com.callnotes.inference.whisper.WhisperSpeed
import com.callnotes.processing.ProcessingStatus
import com.callnotes.util.Prefs

/**
 * Whisper speed preset: accurate (the model as released), balanced (8-bit copy) or fast
 * (5-bit copy, one decoding path). The copies are made on the phone the first time a preset
 * is used, and the measured speed of each preset on this phone is shown.
 */
@Composable
fun SpeedSection(models: ModelManager, prefs: Prefs) {
    val snap by models.state.collectAsStateWithLifecycle()
    val spec = snap.active(ModelRole.TRANSCRIBE) ?: return
    if (spec.engine != InferenceEngine.WHISPER_CPP) return
    val base = models.fileFor(spec)
    val noteVersion by ProcessingStatus.version.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(WhisperSpeed.current(prefs)) }
    var filesVersion by remember { mutableIntStateOf(0) }

    GroupLabel("מהירות")
    CardGroup {
        Column {
            WhisperSpeed.entries.forEachIndexed { i, s ->
                if (i > 0) RowDivider(inset = false)
                val ready = remember(filesVersion, noteVersion) { s.quantType == null || s.fileFor(base).isFile }
                val measured = remember(noteVersion) {
                    prefs.speedStats(s.name).let { (audio, work) -> if (audio >= 30_000) work.toDouble() / audio else null }
                }
                // Battery per 10 minutes of call, from transcriptions measured off the charger.
                val energy = remember(noteVersion) {
                    prefs.energyStats(s.name).let { (milli, audio) -> if (audio >= 60_000) milli / 1000.0 / (audio / 600_000.0) else null }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = selected == s, role = Role.RadioButton) {
                            selected = s; prefs.transcribeSpeed = s.name
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = selected == s, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = MaterialTheme.typography.titleSmall)
                        Text(s.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val status = listOfNotNull(
                            measured?.let { "נמדד בטלפון: %.1f× מאורך השיחה".format(it) },
                            energy?.let { "~%.1f%% סוללה ל-10 דקות שיחה".format(it) },
                            if (!ready) "יוכן בתמלול הבא (~%.1f GB)".format(s.expectedBytes(base) / 1e9) else null,
                            if (s.quantType != null && ready) "מוכן · %.2f GB".format(s.fileFor(base).length() / 1e9) else null,
                        )
                        if (status.isNotEmpty()) Text(
                            status.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            val derived = remember(filesVersion, noteVersion) {
                WhisperSpeed.entries.filter { it.quantType != null }.sumOf { it.fileFor(base).takeIf { f -> f.isFile }?.length() ?: 0L }
            }
            if (derived > 0) TextButton(modifier = Modifier.padding(start = 6.dp), onClick = {
                WhisperSpeed.deleteDerived(base)
                filesVersion++
            }, enabled = !(ProcessingStatus.state.value.running)) {
                Text("מחיקת העותקים המהירים (%.1f GB)".format(derived / 1e9))
            }
        }
    }
    Note("העותק המהיר נוצר בטלפון מהמודל שכבר הורד, בתמלול הראשון אחרי הבחירה (דקה–שתיים, פעם אחת). המודל המלא נשאר.")
}
