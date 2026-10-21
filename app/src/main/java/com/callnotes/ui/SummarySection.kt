package com.callnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.callnotes.data.AnalysisStatus
import com.callnotes.data.CallEntity
import com.callnotes.data.TaskEntity
import com.callnotes.inference.AnalysisPrompt
import com.callnotes.processing.ProcessingStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Gemma's summary and the tasks found in the call, above the transcript. Tasks can be
 * ticked off. Shows progress / retry / "download the model" as appropriate.
 */
@Composable
fun SummarySection(
    c: CallEntity,
    tasks: List<TaskEntity>,
    processing: ProcessingStatus.State,
    queued: Boolean,
    modelInstalled: Boolean,
    autoOn: Boolean,
    onSummarise: () -> Unit,
    onCancel: () -> Unit,
    onTaskDone: (TaskEntity, Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val running = processing.running && processing.callId == c.id &&
        processing.phase in setOf("loading_analyzer", "analyzing", "waiting_memory")
    val analysis = remember(c.analysisJson) { AnalysisPrompt.fromJson(c.analysisJson) }

    SectionHeader("סיכום ומשימות")
    when {
        running || queued -> Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        processing.phase == "loading_analyzer" && running -> "טוען את מודל הסיכום…"
                        processing.phase == "waiting_memory" && running -> "ממתין לזיכרון פנוי — התמלול רץ במקביל…"
                        running -> "מסכם את השיחה…"
                        else -> "ממתין לסיכום…"
                    },
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onCancel) { Text("ביטול") }
            }
            Text("Gemma קורא את השיחה על הטלפון. זה יכול לקחת כמה דקות.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        }

        c.analysisStatus == AnalysisStatus.DONE && analysis != null -> Panel {
            if (analysis.title.isBlank() && analysis.summary.isBlank()) {
                Text("לא נמצא בשיחה תוכן לסיכום.", style = MaterialTheme.typography.bodyMedium)
            } else {
                if (analysis.title.isNotBlank()) Text(analysis.title, style = MaterialTheme.typography.titleMedium)
                if (analysis.summary.isNotBlank()) {
                    Text(analysis.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                }
                Bullets("עיקרי הדברים", analysis.keyPoints)
                Bullets("החלטות", analysis.decisions)
            }
            if (tasks.isNotEmpty()) {
                Text("משימות", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
                tasks.forEach { t -> TaskRow(t, ownerLabel(c, t.owner)) { onTaskDone(t, it) } }
            }
            Bullets("להמשך", analysis.followUps)
            TextButton(onClick = onSummarise, modifier = Modifier.padding(top = 4.dp)) { Text("סכם מחדש") }
        }

        !modelInstalled -> Panel {
            Text("כדי לקבל סיכום ומשימות, הורד מודל Gemma בהגדרות ← סיכומים ומשימות.",
                style = MaterialTheme.typography.bodyMedium)
        }

        c.analysisStatus == AnalysisStatus.FAILED -> Panel {
            Text("הסיכום נכשל", style = MaterialTheme.typography.titleSmall, color = cs.error)
            c.analysisError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            FilledTonalButton(onClick = onSummarise, modifier = Modifier.padding(top = 8.dp)) { Text("נסה שוב") }
        }

        else -> Panel {
            Text(
                if (c.analysisStatus == AnalysisStatus.SKIPPED) "הסיכום בוטל."
                else if (autoOn) "הסיכום יופק אוטומטית אחרי התמלולים שבתור."
                else "השיחה עוד לא סוכמה.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = onSummarise, modifier = Modifier.padding(top = 8.dp)) { Text("סכם עכשיו") }
        }
    }
}

@Composable
private fun Bullets(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
    items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
}

@Composable
private fun TaskRow(t: TaskEntity, owner: String?, onDone: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Checkbox(checked = t.done, onCheckedChange = onDone)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                color = if (t.done) cs.onSurfaceVariant else cs.onSurface,
            )
            val meta = listOfNotNull(owner, due(t)).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            t.quote?.let {
                Text("\"$it\"", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    Spacer(Modifier.size(2.dp))
}

private fun ownerLabel(c: CallEntity, owner: String): String? = when (owner) {
    "me" -> "באחריותי"
    "other" -> "באחריות ${c.contactName ?: "הצד השני"}"
    else -> null
}

private fun due(t: TaskEntity): String? {
    val date = t.dueDate?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrNull() }
    return when {
        date != null && t.duePhrase != null -> "עד $date (${t.duePhrase})"
        date != null -> "עד $date"
        t.duePhrase != null -> t.duePhrase
        else -> null
    }
}
