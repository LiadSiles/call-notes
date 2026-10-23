package com.callnotes.ui

import com.callnotes.processing.Phase
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.callnotes.data.AnalysisStatus
import com.callnotes.data.CallEntity
import com.callnotes.data.TaskEntity
import com.callnotes.inference.AnalysisPrompt
import com.callnotes.inference.SourceFinder
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
    /** Jump to a transcript line (0-based segment index). */
    onShowLine: (Int) -> Unit,
    /** The user corrected speakers since this summary was made. */
    speakersEdited: Boolean,
    /** Transcript lines, to find an item's place when the summary didn't cite one. */
    lineTexts: List<String>,
) {
    val cs = MaterialTheme.colorScheme
    val running = processing.running && processing.callId == c.id &&
        processing.phase in setOf(Phase.LOADING_ANALYZER, Phase.ANALYZING, Phase.WAITING_MEMORY)
    val analysis = remember(c.analysisJson) { AnalysisPrompt.fromJson(c.analysisJson) }

    SectionHeader("סיכום ומשימות")
    when {
        running || queued -> Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        processing.phase == Phase.LOADING_ANALYZER && running -> "טוען את מודל הסיכום…"
                        processing.phase == Phase.WAITING_MEMORY && running -> "ממתין לזיכרון פנוי — התמלול רץ במקביל…"
                        running && processing.progressMax > 1 -> "מסכם · חלק ${processing.progress} מתוך ${processing.progressMax}…"
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
            if (speakersEdited) {
                Text("תיקנת מי אמר מה — הסיכום נוצר לפני התיקון.", style = MaterialTheme.typography.bodySmall, color = cs.error)
                TextButton(onClick = onSummarise) { Text("סכם מחדש לפי התיקון") }
            }
            if (analysis.title.isBlank() && analysis.summary.isBlank()) {
                Text("לא נמצא בשיחה תוכן לסיכום.", style = MaterialTheme.typography.bodyMedium)
            } else {
                if (analysis.title.isNotBlank()) Text(analysis.title, style = MaterialTheme.typography.titleMedium)
                if (!analysis.summaryCertain) {
                    Pill("התמלול לא ברור בחלקו — הסיכום עשוי להיות לא מדויק", Tone.Bad, Modifier.padding(top = 6.dp))
                }
                if (analysis.summary.isNotBlank()) {
                    Text(analysis.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                }
                Points("עיקרי הדברים", analysis.keyPoints, lineTexts, onShowLine)
                Points("החלטות", analysis.decisions, lineTexts, onShowLine)
            }
            if (tasks.isNotEmpty()) {
                Text("משימות", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
                tasks.forEach { t ->
                    val target = remember(t.id, lineTexts.size) {
                        t.firstSegment ?: SourceFinder.bestLine(t.quote ?: t.title, lineTexts)
                    }
                    TaskRow(t, ownerLabel(c, t.owner), com.callnotes.util.Notifications.callLabel(c),
                        target?.let { line -> { onShowLine(line) } }) { onTaskDone(t, it) }
                }
            }
            Points("להמשך", analysis.followUps, lineTexts, onShowLine)
            Text("לחיצה על פריט מראה את המקום בשיחה שממנו הוא נלקח.", style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
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

/** Extracted items; each opens its source line in the transcript. Unverified ones say so. */
@Composable
private fun Points(
    title: String,
    items: List<com.callnotes.inference.CallAnalysis.Point>,
    lineTexts: List<String>,
    onShowLine: (Int) -> Unit,
) {
    if (items.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
    items.forEach { p ->
        // Cited line, else the line its words match (summaries without citations).
        val line = remember(p, lineTexts.size) {
            p.lines.firstOrNull()?.minus(1)?.takeIf { it in lineTexts.indices } ?: SourceFinder.bestLine(p.text, lineTexts)
        }
        Column(
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .then(if (line != null) Modifier.clickable { onShowLine(line) } else Modifier)
                .padding(vertical = 6.dp, horizontal = 4.dp),
        ) {
            Text("• ${p.text}", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, top = 2.dp)) {
                if (line != null) ShowInCall()
                if (!p.certain) {
                    if (line != null) Spacer(Modifier.width(8.dp))
                    Pill("לא בטוח", Tone.Bad)
                }
            }
        }
    }
}

/** The visible cue that an item opens its place in the transcript. */
@Composable
private fun ShowInCall() {
    Text("הצג בשיחה ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TaskRow(t: TaskEntity, owner: String?, callLabel: String, onOpen: (() -> Unit)?, onDone: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Checkbox(checked = t.done, onCheckedChange = onDone)
        Spacer(Modifier.width(4.dp))
        Column(
            Modifier.weight(1f)
                .clip(MaterialTheme.shapes.small)
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(top = 12.dp, bottom = 4.dp),
        ) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                color = if (t.done) cs.onSurfaceVariant else cs.onSurface,
            )
            val meta = listOfNotNull(owner, dueLabel(t.dueDate, t.dueTime, t.duePhrase)).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            t.quote?.let {
                Text("\"$it\"", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                if (onOpen != null) ShowInCall()
                if (!t.certain) {
                    if (onOpen != null) Spacer(Modifier.width(8.dp))
                    Pill("לא בטוח", Tone.Bad)
                }
            }
            if (t.duplicateOf != null) Pill("חוזרת על משימה משיחה קודמת", Tone.Busy, Modifier.padding(top = 6.dp))
            TaskExtras(t, callLabel)
        }
    }
    Spacer(Modifier.size(2.dp))
}

private fun ownerLabel(c: CallEntity, owner: String): String? = when (owner) {
    "me" -> "באחריותי"
    "other" -> "באחריות ${c.contactName ?: "הצד השני"}"
    else -> null
}

