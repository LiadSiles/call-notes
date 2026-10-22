package com.callnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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

    Panel {
        Column {
            Text("שמירת נתונים", style = MaterialTheme.typography.titleSmall)
            Text(
                "מחיקה אוטומטית, פעם ביום. כבוי כברירת מחדל: שום דבר לא נמחק בלי שבחרת.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Choice("מחיקת הקלטות אחרי", "רק של שיחות שכבר תומללו. התמלול, הסיכום והמשימות נשארים.",
                Retention.AUDIO_DAYS, audio) { choose(true, it) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Choice("מחיקת שיחות שלמות אחרי", "הקלטה, תמלול, סיכום ומשימות. שיחה עם משימה פתוחה נשמרת עד שהמשימה מסומנת כבוצעה.",
                Retention.CALL_DAYS, calls) { choose(false, it) }
        }
    }

    pending?.let { (isAudio, days, count) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (isAudio) "למחוק $count הקלטות?" else "למחוק $count שיחות?") },
            text = {
                Text(
                    (if (isAudio) "$count הקלטות ישנות מ-${daysLabel(days)} יימחקו עכשיו, והתמלול שלהן יישאר."
                    else "$count שיחות ישנות מ-${daysLabel(days)} יימחקו עכשיו לגמרי.") +
                        " אי אפשר לשחזר אותן.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    apply(isAudio, days, prefs, { audio = it }, { calls = it })
                    scope.launch(Dispatchers.IO) { Retention.run(context) }
                }) { Text("מחיקה") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("ביטול") } },
        )
    }
}

private fun apply(isAudio: Boolean, days: Int, prefs: Prefs, setAudio: (Int) -> Unit, setCalls: (Int) -> Unit) {
    if (isAudio) { prefs.audioRetentionDays = days; setAudio(days) } else { prefs.callRetentionDays = days; setCalls(days) }
}

private fun daysLabel(days: Int) = when (days) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(title: String, body: String, options: List<Int>, value: Int, onChoose: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        options.forEach { d ->
            FilterChip(selected = value == d, onClick = { if (value != d) onChoose(d) }, label = { Text(daysLabel(d)) })
        }
    }
}

/** App lock switch. Turning it on or off asks for the phone's lock first. */
@Composable
fun LockSection(prefs: Prefs) {
    val activity = LocalContext.current as? androidx.fragment.app.FragmentActivity ?: return
    var on by remember { mutableStateOf(prefs.appLock) }
    var problem by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.padding(top = 8.dp)) {
        Column {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("נעילת האפליקציה", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "טביעת אצבע, זיהוי פנים או קוד הנעילה של הטלפון בכל פתיחה (אחרי 30 שניות ברקע). " +
                            "גם מסתיר את האפליקציה בצילומי מסך ובמסך האפליקציות האחרונות, ואת תוכן ההתראות במסך הנעילה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = on, onCheckedChange = { want ->
                    if (want && !AppLock.available(activity)) {
                        problem = "לא מוגדרת נעילת מסך או טביעת אצבע בטלפון. הגדר נעילת מסך בהגדרות הטלפון ונסה שוב."
                        return@Switch
                    }
                    AppLock.authenticate(activity, if (want) "הפעלת נעילה" else "כיבוי נעילה") { ok ->
                        if (ok) {
                            prefs.appLock = want; on = want
                            AppLock.secureWindow(activity, want)
                        }
                    }
                })
            }
            problem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Compression switch (storage, not privacy, but it lives with the other "what's kept" settings). */
@Composable
fun CompressionSection(prefs: Prefs) {
    var on by remember { mutableStateOf(prefs.compressRecordings) }
    Panel(Modifier.padding(top = 8.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("דחיסת הקלטות", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "אחרי התמלול ההקלטה נשמרת כ-AAC: בערך פי 8 פחות מקום, בלי הבדל נשמע בדיבור. " +
                        "הקלטות קיימות נדחסות בהדרגה. הדחיסה סופית — כיבוי חל רק על הקלטות חדשות.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(checked = on, onCheckedChange = { on = it; prefs.compressRecordings = it })
        }
    }
}
