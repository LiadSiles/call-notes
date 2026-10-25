package com.callnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.processing.ProcessingLog
import com.callnotes.processing.ProcessingStatus
import com.callnotes.processing.TranscriptionService
import com.callnotes.util.Energy
import com.callnotes.util.Prefs

/*
 * Automatic processing settings. Transcription and summaries have their own switches, each
 * on its own settings page; the call-length limit lives with transcription and applies to
 * both (a summary needs a transcript first).
 */

/**
 * The owner's name and a list of names and words that come up in calls. Whisper is told
 * to expect them (with the other side's contact name) and the summary knows them, so names
 * come out spelled right and "ליעד, תשלח…" is understood as said to the owner.
 */
@Composable
fun PersonalWordsSettings(prefs: Prefs) {
    var name by remember { mutableStateOf(prefs.ownerName) }
    var words by remember { mutableStateOf(prefs.vocabulary) }
    CardGroup {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40); prefs.ownerName = name },
                label = { Text("השם שלך") },
                placeholder = { Text("כמו שפונים אליך בשיחות") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(6.dp))
            OutlinedTextField(
                value = words,
                onValueChange = { words = it.take(400); prefs.vocabulary = words },
                label = { Text("שמות ומילים שחוזרים בשיחות") },
                placeholder = { Text("למשל: נתב״ג, הצעת מחיר, משה לוי") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Note("מופרדים בפסיקים, בכתיב הנכון. התמלול מצפה למילים האלה ולשם איש הקשר, והסיכום יודע שמי שנקרא בשמך הוא אתה. " +
        "עדיף רשימה קצרה של מילים שבאמת חוזרות: רק כ-150 התווים הראשונים נכנסים לתמלול.")
}

/** Transcription page: automatic switch, the length limit, and the processing log. */
@Composable
fun AutoTranscribeSettings(prefs: Prefs) {
    val context = LocalContext.current
    val noteVersion by ProcessingStatus.version.collectAsStateWithLifecycle()
    // Re-read on every note: "stop all" turns the switch off from elsewhere.
    var auto by remember(noteVersion) { mutableStateOf(prefs.autoTranscribe) }
    val lastRun = remember(noteVersion) { prefs.lastProcessing }
    var showLog by remember { mutableStateOf(false) }
    CardGroup {
        SwitchRow("תמלול אוטומטי", "כל הקלטה מתומללת ברקע בסיומה.", auto) {
            auto = it; prefs.autoTranscribe = it
            if (it) TranscriptionService.start(context)
        }
        RowDivider(inset = false)
        OnlyWhileChargingRow(prefs, ProcessingStep.TRANSCRIBE, enabled = auto)
        RowDivider(inset = false)
        LengthLimit(prefs)
    }
    GroupLabel("יומן")
    CardGroup {
        InfoRow("יומן עיבוד", lastRun?.let { "הרצה אחרונה: $it" } ?: "העיבוד עוד לא רץ.", onClick = { showLog = true }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
    if (showLog) TraceDialog(ProcessingLog.file(context), onDismiss = { showLog = false }, title = "יומן עיבוד")
}

/**
 * How summaries are made: "precise" (three questions per call — about, commitments, review)
 * or "quick" (one). Either way the whole call is read; long calls in parts.
 */
@Composable
fun SummaryModeSettings(prefs: Prefs) {
    var precise by remember { mutableStateOf(prefs.preciseSummary) }
    var thinking by remember { mutableStateOf(prefs.summaryThinking) }
    CardGroup {
        ChipChoice(
            "אופן הסיכום",
            if (precise) "שלושה שלבים: על מה דובר, חילוץ כל ההתחייבויות עם ציטוט, ובדיקה חוזרת שלהן. מדויק יותר, לוקח יותר זמן."
            else "שאלה אחת לכל הסיכום והמשימות. מהיר יותר, פחות מדויק במשימות.",
            listOf(true, false),
            precise,
            label = { if (it) "מדויק" else "מהיר" },
        ) { precise = it; prefs.preciseSummary = it }
        RowDivider(inset = false)
        SwitchRow(
            "חשיבה לפני תשובה (ניסיוני)",
            "Gemma חושב לפני כל תשובה, כמו טיוטה שלא מוצגת. יכול לדייק יותר, אבל כל סיכום לוקח פי 2–4 זמן וסוללה — " +
                "מתאים במיוחד עם \"רק בזמן טעינה\".",
            thinking,
        ) { thinking = it; prefs.summaryThinking = it }
    }
    Note("כל השיחה נלקחת בחשבון: שיחה ארוכה מחולקת לחלקים, כל חלק מסוכם, והחלקים מאוחדים לסיכום אחד בלי לאבד פרטים. " +
        "שינוי חל על הסיכומים הבאים; שיחה שכבר סוכמה אפשר לסכם מחדש במסך שלה.")
}

/** Summaries page: automatic switch; the length limit is shown, set on the transcription page. */
@Composable
fun AutoSummarySettings(prefs: Prefs) {
    val context = LocalContext.current
    val noteVersion by ProcessingStatus.version.collectAsStateWithLifecycle()
    var auto by remember(noteVersion) { mutableStateOf(prefs.autoSummarize) }
    val limit = remember(noteVersion) { prefs.autoMaxMinutes }
    CardGroup {
        SwitchRow("סיכום אוטומטי", "כל שיחה שתומללה מסוכמת ברקע, עם משימות.", auto) {
            auto = it; prefs.autoSummarize = it
            if (it) TranscriptionService.start(context)
        }
        RowDivider(inset = false)
        OnlyWhileChargingRow(prefs, ProcessingStep.SUMMARY, enabled = auto)
    }
    Note(
        (if (limit > 0) "חל רק על שיחות של עד $limit דקות (מוגדר בעמוד התמלול). " else "") +
            "אפשר תמיד לסכם שיחה בלחיצה על \"סכם עכשיו\" במסך שלה."
    )
}

/**
 * Optional maximum call length for automatic processing: a switch, then the exact
 * number of minutes by slider (1–120) or typed (1–600). Longer calls wait for a manual
 * "transcribe"; switching the limit off keeps the last value for next time.
 */
@Composable
private fun LengthLimit(prefs: Prefs) {
    var minutes by remember { mutableIntStateOf(prefs.autoMaxMinutes) }
    var typed by remember { mutableStateOf((if (minutes > 0) minutes else prefs.autoMaxLastMinutes).toString()) }
    val on = minutes > 0

    fun set(value: Int) {
        val v = value.coerceIn(Prefs.AUTO_MAX_MIN, Prefs.AUTO_MAX_TYPED)
        minutes = v
        typed = v.toString()
        prefs.autoMaxMinutes = v
        prefs.autoMaxLastMinutes = v
        ProcessingStatus.touch()
    }

    SwitchRow(
        "הגבלה לפי אורך שיחה",
        if (on) "שיחות של עד $minutes דקות מעובדות אוטומטית; ארוכות יותר — בלחיצה על \"תמלל\"."
        else "כל שיחה מעובדת אוטומטית, בלי קשר לאורך.",
        on,
    ) { enable ->
        if (enable) set(prefs.autoMaxLastMinutes) else { minutes = 0; prefs.autoMaxMinutes = 0; ProcessingStatus.touch() }
    }
    if (!on) return
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Slider(
                value = minutes.coerceAtMost(Prefs.AUTO_MAX_SLIDER).toFloat(),
                onValueChange = { v ->
                    // Live feedback while dragging; saved when the finger lifts.
                    minutes = kotlin.math.round(v).toInt(); typed = minutes.toString()
                },
                onValueChangeFinished = { set(minutes) },
                // Continuous track (120 tick marks would be clutter), rounded to whole minutes.
                valueRange = Prefs.AUTO_MAX_MIN.toFloat()..Prefs.AUTO_MAX_SLIDER.toFloat(),
            )
            Row {
                Text("דקה", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("שעתיים", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(14.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { text ->
                typed = text.filter(Char::isDigit).take(3)
                typed.toIntOrNull()?.takeIf { it >= Prefs.AUTO_MAX_MIN }?.let { set(it) }
            },
            singleLine = true,
            suffix = { Text("דק׳") },
            textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.width(104.dp),
        )
    }
}

/** The two automatic steps that can each wait for the charger. */
enum class ProcessingStep { TRANSCRIBE, SUMMARY }

/**
 * "Only while charging" for one step. Turning it on while unplugged pauses that step's
 * automatic work in progress (manual requests continue); turning it off resumes the queue.
 */
@Composable
fun OnlyWhileChargingRow(prefs: Prefs, step: ProcessingStep, enabled: Boolean = true) {
    val context = LocalContext.current
    val transcribe = step == ProcessingStep.TRANSCRIBE
    var on by remember { mutableStateOf(if (transcribe) prefs.transcribeOnlyCharging else prefs.summarizeOnlyCharging) }
    SwitchRow(
        if (transcribe) "תמלול רק בזמן טעינה" else "סיכום רק בזמן טעינה",
        if (transcribe) "התמלול האוטומטי מחכה למטען (למשל ללילה)." else "הסיכום האוטומטי מחכה למטען (למשל ללילה).",
        on,
        enabled = enabled,
    ) {
        on = it
        if (transcribe) prefs.transcribeOnlyCharging = it else prefs.summarizeOnlyCharging = it
        ProcessingStatus.touch()
        if (it && !Energy.pluggedIn(context)) TranscriptionService.pauseForCharger(context)
        else TranscriptionService.start(context)
    }
}
