package com.callnotes.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.CallNotesApp
import com.callnotes.util.Prefs
import com.callnotes.util.Retention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the user chose to keep, and for how long. */
@Composable
fun RetentionSection(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = (context.applicationContext as CallNotesApp).database.calls()
    var audio by remember { mutableStateOf(prefs.audioRetentionDays) }
    var calls by remember { mutableStateOf(prefs.callRetentionDays) }
    /** A choice that deletes existing items right away, waiting for confirmation. */
    var pending by remember { mutableStateOf<Triple<Boolean, Int, Int>?>(null) } // (isAudio, days, count)

    fun choose(isAudio: Boolean, days: Int) {
        scope.launch {
            val count = if (days == 0) 0 else withContext(Dispatchers.IO) {
                val before = System.currentTimeMillis() - days * 86_400_000L
                if (isAudio) dao.audioToExpire(before).size else dao.callsToExpire(before).size
            }
            if (count > 0) pending = Triple(isAudio, days, count) else apply(isAudio, days, prefs, { audio = it }, { calls = it })
        }
    }

    CardGroup {
        Choice("מחיקת הקלטות אחרי", "של שיחות שתומללו. התמלול והסיכום נשארים.", Retention.AUDIO_DAYS, audio) { choose(true, it) }
        RowDivider(inset = false)
        Choice("מחיקת שיחות שלמות אחרי", "כולל תמלול, סיכום ומשימות.", Retention.CALL_DAYS, calls) { choose(false, it) }
    }
    Note("כבוי כברירת מחדל — שום דבר לא נמחק בלי שבחרת. שיחה עם משימה פתוחה נשמרת עד שהמשימה מסומנת כבוצעה. הבדיקה רצה פעם ביום.")

    pending?.let { (isAudio, days, count) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (isAudio) "למחוק $count הקלטות?" else "למחוק $count שיחות?") },
            text = {
                Text(
                    (if (isAudio) "$count הקלטות ישנות מ${daysLabel(days)} יימחקו עכשיו, והתמלול שלהן יישאר."
                    else "$count שיחות ישנות מ${daysLabel(days)} יימחקו עכשיו לגמרי.") +
                        " אי אפשר לשחזר אותן.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    apply(isAudio, days, prefs, { audio = it }, { calls = it })
                    scope.launch(Dispatchers.IO) { Retention.run(context) }
                }) { Text("מחיקה", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("ביטול") } },
        )
    }
}

private fun apply(isAudio: Boolean, days: Int, prefs: Prefs, setAudio: (Int) -> Unit, setCalls: (Int) -> Unit) {
    if (isAudio) { prefs.audioRetentionDays = days; setAudio(days) } else { prefs.callRetentionDays = days; setCalls(days) }
}

internal fun daysLabel(days: Int) = when (days) {
    0 -> "אף פעם"
    1 -> "יום"
    7 -> "שבוע"
    30 -> "חודש"
    90 -> "3 חודשים"
    180 -> "חצי שנה"
    365 -> "שנה"
    730 -> "שנתיים"
    else -> "$days ימים"
}

@Composable
private fun Choice(title: String, body: String, options: List<Int>, value: Int, onChoose: (Int) -> Unit) =
    ChipChoice(title, body, options, value, ::daysLabel, onChoose = onChoose)

/** App lock switch. Turning it on or off asks for the phone's lock first. */
@Composable
fun LockSection(prefs: Prefs) {
    val activity = androidx.activity.compose.LocalActivity.current as? androidx.fragment.app.FragmentActivity ?: return
    var on by remember { mutableStateOf(prefs.appLock) }
    var problem by remember { mutableStateOf<String?>(null) }
    CardGroup {
        SwitchRow("נעילת האפליקציה", "טביעת אצבע, פנים או קוד הטלפון בכל פתיחה.", on) { want ->
            if (want && !AppLock.available(activity)) {
                problem = "לא מוגדרת נעילת מסך או טביעת אצבע בטלפון. הגדר נעילת מסך בהגדרות הטלפון ונסה שוב."
                return@SwitchRow
            }
            AppLock.authenticate(activity, if (want) "הפעלת נעילה" else "כיבוי נעילה") { ok ->
                if (ok) {
                    prefs.appLock = want; on = want
                    AppLock.secureWindow(activity, want)
                }
            }
        }
    }
    problem?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 10.dp, top = 8.dp))
    }
    Note("אחרי 30 שניות ברקע. גם מסתיר את האפליקציה בצילומי מסך ובמסך האפליקציות האחרונות, ואת תוכן ההתראות במסך הנעילה.")
}

/** Compression switch: storage, but it lives with the other "what's kept" settings. */
@Composable
fun CompressionSection(prefs: Prefs) {
    var on by remember { mutableStateOf(prefs.compressRecordings) }
    CardGroup {
        SwitchRow("דחיסת הקלטות", "אחרי התמלול: בערך פי 8 פחות מקום, בלי הבדל נשמע בדיבור.", on) {
            on = it; prefs.compressRecordings = it
        }
    }
    Note("הקלטות קיימות נדחסות בהדרגה. הדחיסה סופית — כיבוי חל רק על הקלטות חדשות.")
}

/** Optional extra encryption of the database and recordings. */
@Composable
fun EncryptionSection(prefs: Prefs) {
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current
    val app = context.applicationContext as CallNotesApp
    var on by remember { mutableStateOf(prefs.encryptData) }
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    var keyLost by remember { mutableStateOf(prefs.dataKeyLost) }
    val progress by com.callnotes.security.DataProtection.progress.collectAsStateWithLifecycle()
    val service by com.callnotes.capture.ServiceStatus.state.collectAsStateWithLifecycle()
    val processing by com.callnotes.processing.ProcessingStatus.state.collectAsStateWithLifecycle()
    val summary by com.callnotes.processing.ProcessingStatus.summary.collectAsStateWithLifecycle()
    val dbPending = remember(on) { !com.callnotes.security.DataProtection.databaseMatchesSetting(context) }

    CardGroup {
        SwitchRow("הצפנה נוספת", "מסד הנתונים וההקלטות ב-AES-256, במפתח ששמור רק בטלפון.", on) { confirm = it }
        if (progress.running || dbPending) RowDivider(inset = false)
        if (progress.running) {
            InfoRow("${if (on) "מצפין" else "מפענח"} הקלטות", "${progress.done} מתוך ${progress.total}")
        }
        if (dbPending) {
            val busy = service.recordingNow || processing.running || summary.running || progress.running
            InfoRow(
                if (on) "מסד הנתונים יוצפן בהפעלה הבאה" else "ההצפנה תוסר בהפעלה הבאה",
                if (busy) "אפשר להפעיל מחדש אחרי שההקלטה או העיבוד יסתיימו." else null,
            ) {
                TextButton(onClick = { activity?.let { RestartActivity.restart(it) } }, enabled = !busy) { Text("הפעלה מחדש") }
            }
        }
    }
    Note("אנדרואיד כבר מצפין את נתוני האפליקציה עם נעילת הטלפון. ההצפנה הנוספת מגינה גם על עותק של התיקייה מטלפון פרוץ או בבדיקה פורנזית. הקלטה מוצפנת אחרי שתומללה.")
    if (keyLost) {
        Text(
            "מפתח ההצפנה נמחק מהטלפון, ולכן הנתונים המוצפנים הקודמים לא ניתנים לקריאה. האפליקציה התחילה מחדש עם נתונים ריקים.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 10.dp, top = 8.dp),
        )
        TextButton(onClick = { prefs.dataKeyLost = false; keyLost = false }) { Text("הבנתי") }
    }

    confirm?.let { want ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (want) "להפעיל הצפנה נוספת?" else "לכבות את ההצפנה הנוספת?") },
            text = {
                Text(
                    if (want) "ההקלטות שכבר תומללו יוצפנו עכשיו ברקע, ומסד הנתונים — בהפעלה הבאה של האפליקציה. " +
                        "המפתח קיים רק בטלפון הזה: אם הוא יימחק (נדיר מאוד — למשל איפוס של אבטחת הטלפון), הנתונים המוצפנים לא יהיו ניתנים לשחזור."
                    else "ההקלטות יפוענחו עכשיו ברקע, ומסד הנתונים — בהפעלה הבאה של האפליקציה."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    prefs.encryptData = want; on = want
                    app.launchBackground { com.callnotes.security.DataProtection.applyToAll(app) }
                }) { Text(if (want) "הפעלה" else "כיבוי") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("ביטול") } },
        )
    }
}
