package com.callnotes.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.CallNotesApp
import com.callnotes.capture.CallRecorderService
import com.callnotes.capture.ServiceStatus
import com.callnotes.processing.ProcessingStatus
import com.callnotes.processing.TranscriptionService
import com.callnotes.data.CallEntity
import com.callnotes.data.CallKind
import com.callnotes.data.CallStatus
import com.callnotes.data.SpeakerResult
import com.callnotes.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Settings page bodies: setup checklist, optional permissions, and the switch groups for
 * recording, battery and reminders. Each group is a [CardGroup] of [SwitchRow]s with any
 * longer explanation in a [Note] below it.
 */

@Composable
internal fun SetupSection(
    setup: SetupState,
    onPermissions: () -> Unit,
    onAccessibility: () -> Unit,
    onAppDetails: () -> Unit,
    onBattery: () -> Unit,
    onBatteryUndo: () -> Unit,
    onAutostart: () -> Unit,
) {
    val a11yOn = setup.a11yOn
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            

            SetupRow(
                done = setup.permissionsOk,
                title = "1. הרשאות",
                body = "מיקרופון (ההקלטה עצמה) ומצב טלפון (לדעת מתי שיחה מתחילה ומסתיימת).",
                action = "אישור הרשאות",
                onAction = onPermissions,
                undo = "ניהול הרשאות" to onAppDetails,
            )
            if (!setup.permissionsOk) {
                TextButton(onClick = onAppDetails) { Text("לא נפתח חלון? אשר מפרטי האפליקציה ← הרשאות") }
            }
            SetupRow(
                done = a11yOn,
                title = "2. שירות ההקלטה",
                body = "בהגדרות הנגישות: אפליקציות מותקנות ← \"סיכומי שיחות – הקלטה\" ← הפעלה. " +
                    "אנדרואיד מתיר הקלטה בזמן שיחה רק לשירות כזה.",
                action = "פתיחת הגדרות נגישות",
                onAction = onAccessibility,
                undo = "כיבוי בהגדרות הנגישות" to onAccessibility,
            )
            if (!a11yOn && Build.VERSION.SDK_INT >= 33) {
                Text(
                    "אם ההפעלה חסומה (\"הגדרה מוגבלת\"): פתח את פרטי האפליקציה, לחץ על ⋮ למעלה " +
                        "ובחר \"אפשר הגדרות מוגבלות\". אחר כך חזור לשלב 2.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onAppDetails) { Text("פרטי האפליקציה") }
            }
            SetupRow(
                done = setup.batteryOk,
                title = "3. ללא הגבלת סוללה",
                body = "כדי שהמערכת לא תעצור את השירות ברקע.",
                action = "ביטול הגבלה",
                onAction = onBattery,
                undo = "החזרת הגבלת הסוללה" to onBatteryUndo,
            )
            if (SetupChecks.isXiaomiFamily) {
                SetupRow(
                    done = null,
                    title = "4. הפעלה אוטומטית (Xiaomi)",
                    body = "ב-HyperOS יש להפעיל \"הפעלה אוטומטית\" לאפליקציה, אחרת השירות עלול להיעצר. " +
                        "לא ניתן לבדוק זאת אוטומטית.",
                    action = "פתיחת הגדרה",
                    onAction = onAutostart,
                )
            }
        }
    }
}

@Composable
internal fun OptionalSection(
    setup: SetupState,
    onCallLog: () -> Unit,
    onNotifications: () -> Unit,
    onAppDetails: () -> Unit,
) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            
            Text(
                "ההקלטה עובדת גם בלי אלה.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OptionalRow(
                title = "יומן שיחות",
                body = "מוסיף לכל שיחה מספר, שם וכיוון, ומוחק הקלטות של שיחות יוצאות שלא נענו.",
                granted = setup.callLogOk,
                onGrant = onCallLog,
                onRevoke = onAppDetails,
            )
            OptionalRow(
                title = "התראות",
                body = "התראה בזמן הקלטה ועל תקלות.",
                granted = setup.notificationsOk,
                onGrant = onNotifications,
                onRevoke = onAppDetails,
            )
            if (!setup.callLogOk || !setup.notificationsOk) {
                TextButton(onClick = onAppDetails) { Text("לא נפתח חלון? פרטי האפליקציה ← הרשאות") }
            }
        }
    }
}

@Composable
private fun OptionalRow(title: String, body: String, granted: Boolean, onGrant: () -> Unit, onRevoke: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (granted) Pill("מאושר", Tone.Good, Modifier.padding(start = 8.dp))
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        // Android lets an app ask for a permission but not give it back: revoking opens the app's settings.
        if (granted) TextButton(onClick = onRevoke) { Text("ביטול") }
        else TextButton(onClick = onGrant) { Text("אישור") }
    }
}

/**
 * [done] null = can't be verified automatically. [undo] = label + action shown once the
 * step is done, so every setup step can be reversed (Android only allows that in its own
 * settings screens, so these open the right one).
 */
@Composable
private fun SetupRow(
    done: Boolean?,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    undo: Pair<String, () -> Unit>? = null,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (done == true) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = when (done) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (done != true) {
                FilledTonalButton(onClick = onAction, modifier = Modifier.padding(top = 6.dp)) { Text(action) }
            } else if (undo != null) {
                TextButton(onClick = undo.second, contentPadding = PaddingValues(0.dp)) { Text(undo.first) }
            }
        }
    }
}

/** Recording switches: master, internet calls, automatic speaker. */
@Composable
internal fun RecordingSettings(prefs: Prefs) {
    var recording by remember { mutableStateOf(prefs.recordingEnabled) }
    var autoSpeaker by remember { mutableStateOf(prefs.autoSpeaker) }
    var voip by remember { mutableStateOf(prefs.recordVoip) }
    CardGroup {
        SwitchRow("הקלטת שיחות", "כל שיחה מוקלטת אוטומטית מתחילתה ועד סופה.", recording) {
            recording = it; prefs.recordingEnabled = it
        }
        RowDivider(inset = false)
        SwitchRow("שיחות אינטרנט", "WhatsApp, טלגרם, Meet ודומיהן. ניסיוני, ללא שם הצד השני.", voip, enabled = recording) {
            voip = it; prefs.recordVoip = it
        }
        RowDivider(inset = false)
        SwitchRow("רמקול אוטומטי", "רק אם הצד השני נשמע חלש בהקלטות.", autoSpeaker) {
            autoSpeaker = it; prefs.autoSpeaker = it
        }
    }
    Note("ברמקול אוטומטי כל שיחה עוברת לרמקול; הוא לא פועל כשמחוברות אוזניות. בשיחות אינטרנט ייתכן שבחלק מהמכשירים הצד השני לא יישמע.")
}

/** Battery switches: pause in battery saver, process only while charging. */
@Composable
internal fun BatterySettings(prefs: Prefs) {
    var batterySaver by remember { mutableStateOf(prefs.pauseInBatterySaver) }
    var charging by remember { mutableStateOf(prefs.onlyWhileCharging) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    CardGroup {
        SwitchRow("עיבוד רק בזמן טעינה", "ההקלטה כרגילה; התמלול והסיכום מחכים למטען.", charging) {
            charging = it; prefs.onlyWhileCharging = it
            if (!it && prefs.anyAuto) com.callnotes.processing.TranscriptionService.start(ctx)
        }
        RowDivider(inset = false)
        SwitchRow("השהיה בחיסכון סוללה", "בזמן חיסכון סוללה לא מקליטים ולא מעבדים.", batterySaver) {
            batterySaver = it; prefs.pauseInBatterySaver = it
        }
    }
    Note("לחיצה ידנית על \"תמלל\" או \"סכם\" עובדת גם בלי מטען. שיחה שכבר מוקלטת ממשיכה עד סופה, ועבודה שבתור ממשיכה כשהמגבלה מוסרת.")
    Note("כמה סוללה כל פעולה צרכה מופיע ב\"פרטים טכניים\" של כל שיחה, וכמה צורכת כל מהירות תמלול — בעמוד התמלול.")
}

/** Due-date reminders switch. */
@Composable
internal fun ReminderSettings(prefs: Prefs) {
    var reminders by remember { mutableStateOf(prefs.taskReminders) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    CardGroup {
        SwitchRow(
            "תזכורת ביום היעד",
            "התראה בסביבות ${com.callnotes.util.TaskReminders.HOUR}:00 ביום שמשימה פתוחה צריכה להתבצע.",
            reminders,
        ) { reminders = it; prefs.taskReminders = it; com.callnotes.util.TaskReminders.reschedule(ctx) }
    }
}


