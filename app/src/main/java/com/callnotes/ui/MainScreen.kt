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

/** Below this peak level (~-40 dBFS) the recording is effectively silent. */
private const val SILENT_PEAK = 330

private data class SetupState(
    val permissionsOk: Boolean,
    val a11yOn: Boolean,
    val batteryOk: Boolean,
    val callLogOk: Boolean,
    val notificationsOk: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val scope = rememberCoroutineScope()

    // Re-read system settings each time the user comes back from a settings screen.
    var refresh by remember { mutableIntStateOf(0) }
    val prefs = remember { Prefs(context) }
    val kickQueue: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            if (prefs.autoTranscribe && app.database.calls().pendingCount() > 0) {
                TranscriptionService.start(context)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh++
        CallRecorderService.refreshFromUi() // start call detection if permissions just arrived
        kickQueue() // recordings that couldn't start processing in the background
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            try {
                Importer.import(context, uri)
                if (prefs.autoTranscribe) TranscriptionService.start(context)
            } catch (e: Exception) {
                // Unreadable file: nothing was added.
            }
        }
    }
    val processing by ProcessingStatus.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(setOf<Long>()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh++
        CallRecorderService.refreshFromUi()
    }

    val service by ServiceStatus.state.collectAsStateWithLifecycle()
    val calls by app.database.calls().observeAll().collectAsStateWithLifecycle(emptyList())

    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    // Keep the seek bar moving while something plays.
    LaunchedEffect(player.isPlaying) {
        while (player.isPlaying) {
            player.refreshPosition()
            delay(200)
        }
    }
    var pendingDelete by remember { mutableStateOf<CallEntity?>(null) }
    var traceOf by remember { mutableStateOf<File?>(null) }

    val setup = remember(refresh) {
        SetupState(
            permissionsOk = SetupChecks.allGranted(context, SetupChecks.requiredPermissions),
            a11yOn = SetupChecks.accessibilityEnabled(context),
            batteryOk = SetupChecks.batteryUnrestricted(context),
            callLogOk = SetupChecks.granted(context, SetupChecks.CALL_LOG),
            notificationsOk = SetupChecks.allGranted(context, SetupChecks.notificationPermission),
        )
    }
    val ready = setup.permissionsOk && setup.a11yOn && service.connected && service.monitoring

    Scaffold(
        topBar = { TopAppBar(title = { Text("סיכומי שיחות") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(ready, service.recordingNow) }

            item {
                SetupSection(
                    setup = setup,
                    onPermissions = { permissionLauncher.launch(SetupChecks.requiredPermissions) },
                    onAccessibility = { context.startActivity(SetupChecks.accessibilitySettings()) },
                    onAppDetails = { context.startActivity(SetupChecks.appDetails(context)) },
                    onBattery = { context.startActivity(SetupChecks.batteryRequest(context)) },
                    onAutostart = { SetupChecks.startXiaomiAutostart(context) },
                )
            }

            item {
                OptionalSection(
                    setup = setup,
                    onCallLog = { permissionLauncher.launch(arrayOf(SetupChecks.CALL_LOG)) },
                    onNotifications = { permissionLauncher.launch(SetupChecks.notificationPermission) },
                    onAppDetails = { context.startActivity(SetupChecks.appDetails(context)) },
                )
            }

            item { SettingsSection(prefs) }

            item { ModelCard(app.models, prefs, onReady = kickQueue) }

            item {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("שיחות", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                        Text("ייבוא קובץ אודיו")
                    }
                }
            }

            if (calls.isEmpty()) {
                item {
                    Text(
                        "עדיין אין שיחות מוקלטות. אחרי שההגדרה תושלם, כל שיחה תופיע כאן.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(calls, key = { it.id }) { call ->
                CallCard(
                    call = call,
                    player = player,
                    processing = processing,
                    expanded = call.id in expanded,
                    onToggleTranscript = {
                        expanded = if (call.id in expanded) expanded - call.id else expanded + call.id
                    },
                    transcript = {
                        TranscriptView(app.database.calls(), call.id) { ms ->
                            call.filePath?.let { player.playFrom(call.id, it, ms.toInt()) }
                        }
                    },
                    onTranscribe = {
                        scope.launch(Dispatchers.IO) {
                            app.database.calls().setStatus(call.id, CallStatus.RECORDED, null, null)
                            TranscriptionService.start(context)
                        }
                    },
                    onTrace = call.filePath?.let { path ->
                        com.callnotes.capture.CallTrace.fileFor(File(path)).takeIf { it.exists() }
                    }?.let { f -> { traceOf = f } },
                    onPlay = { call.filePath?.let { player.toggle(call.id, it) } },
                    onShare = { call.filePath?.let { share(context, it) } },
                    onDelete = { pendingDelete = call },
                )
            }
        }
    }

    traceOf?.let { file -> TraceDialog(file, onDismiss = { traceOf = null }) }

    pendingDelete?.let { call ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("למחוק את השיחה?") },
            text = { Text("ההקלטה תימחק מהמכשיר לצמיתות.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (player.loadedId == call.id) player.release()
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            call.filePath?.let {
                                File(it).delete()
                                com.callnotes.capture.CallTrace.fileFor(File(it)).delete()
                            }
                            app.database.calls().delete(call.id)
                        }
                    }
                }) { Text("מחיקה") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("ביטול") } },
        )
    }
}

@Composable
private fun StatusCard(ready: Boolean, recordingNow: Boolean) {
    val (title, text) = when {
        recordingNow -> "מקליט עכשיו" to "השיחה הנוכחית מוקלטת."
        ready -> "ההקלטה פעילה" to "כל שיחה תוקלט אוטומטית ותופיע ברשימה בסיומה."
        else -> "ההגדרה לא הושלמה" to "השלם את השלבים למטה כדי שהשיחות יוקלטו."
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready || recordingNow) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SetupSection(
    setup: SetupState,
    onPermissions: () -> Unit,
    onAccessibility: () -> Unit,
    onAppDetails: () -> Unit,
    onBattery: () -> Unit,
    onAutostart: () -> Unit,
) {
    val a11yOn = setup.a11yOn
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("הגדרה", style = MaterialTheme.typography.titleMedium)

            SetupRow(
                done = setup.permissionsOk,
                title = "1. הרשאות",
                body = "מיקרופון (ההקלטה עצמה) ומצב טלפון (לדעת מתי שיחה מתחילה ומסתיימת).",
                action = "אישור הרשאות",
                onAction = onPermissions,
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
private fun OptionalSection(
    setup: SetupState,
    onCallLog: () -> Unit,
    onNotifications: () -> Unit,
    onAppDetails: () -> Unit,
) {
    if (setup.callLogOk && setup.notificationsOk) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("אופציונלי", style = MaterialTheme.typography.titleMedium)
            Text(
                "ההקלטה עובדת גם בלי אלה.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!setup.callLogOk) {
                OptionalRow(
                    title = "יומן שיחות",
                    body = "מוסיף לכל שיחה מספר, שם וכיוון, ומוחק הקלטות של שיחות יוצאות שלא נענו.",
                    action = "אישור",
                    onAction = onCallLog,
                )
            }
            if (!setup.notificationsOk) {
                OptionalRow(
                    title = "התראות",
                    body = "התראה בזמן הקלטה ועל תקלות.",
                    action = "אישור",
                    onAction = onNotifications,
                )
            }
            TextButton(onClick = onAppDetails) { Text("לא נפתח חלון? פרטי האפליקציה ← הרשאות") }
        }
    }
}

@Composable
private fun OptionalRow(title: String, body: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** [done] null = can't be verified automatically. */
@Composable
private fun SetupRow(done: Boolean?, title: String, body: String, action: String, onAction: () -> Unit) {
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
            }
        }
    }
}

@Composable
private fun SettingsSection(prefs: Prefs) {
    var recording by remember { mutableStateOf(prefs.recordingEnabled) }
    var autoSpeaker by remember { mutableStateOf(prefs.autoSpeaker) }
    var voip by remember { mutableStateOf(prefs.recordVoip) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("הגדרות", style = MaterialTheme.typography.titleMedium)
            ToggleRow("הקלטת שיחות", "מתג ראשי", recording) {
                recording = it; prefs.recordingEnabled = it
            }
            HorizontalDivider()
            ToggleRow(
                "שיחות אינטרנט (ניסיוני)",
                "מקליט גם שיחות WhatsApp, טלגרם, Meet וכדומה. ללא שם הצד השני. " +
                    "ייתכן שבחלק מהמכשירים הצד השני לא יישמע.",
                voip,
                enabled = recording,
            ) { voip = it; prefs.recordVoip = it }
            HorizontalDivider()
            ToggleRow(
                "רמקול אוטומטי",
                "הפעל רק אם בהקלטות הצד השני נשמע חלש. מעביר כל שיחה לרמקול; לא פועל כשמחוברות אוזניות.",
                autoSpeaker,
            ) { autoSpeaker = it; prefs.autoSpeaker = it }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallCard(
    call: CallEntity,
    player: AudioPlayer,
    processing: ProcessingStatus.State,
    expanded: Boolean,
    onToggleTranscript: () -> Unit,
    transcript: @Composable () -> Unit,
    onTranscribe: () -> Unit,
    onTrace: (() -> Unit)?,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val failed = call.status == CallStatus.RECORD_FAILED
    val quiet = !failed && (call.silenced || call.peakLevel < SILENT_PEAK)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val voip = call.kind == CallKind.VOIP
                    Text(
                        call.contactName ?: call.number ?: if (voip) "שיחת אינטרנט" else "מספר לא ידוע",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val direction = when {
                        call.kind == CallKind.IMPORT -> "קובץ מיובא"
                        voip -> "אינטרנט"
                        call.incoming -> "נכנסת"
                        else -> "יוצאת"
                    }
                    Text(
                        "$direction · ${formatDate(call.startedAt)} · " +
                            formatDuration(call.endedAt - call.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!failed) {
                    val playing = player.loadedId == call.id && player.isPlaying
                    IconButton(onClick = onPlay) {
                        Icon(
                            if (playing) PauseIcon else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "השהיה" else "השמעה",
                        )
                    }
                    IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "שיתוף") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "מחיקה") }
            }

            if (player.loadedId == call.id && player.durationMs > 0) {
                SeekBar(player)
            }

            // Transcription state.
            when (call.status) {
                CallStatus.TRANSCRIBED -> TextButton(onClick = onToggleTranscript) {
                    Text(if (expanded) "הסתרת התמלול" else "תמלול")
                }
                CallStatus.TRANSCRIBING -> {
                    val label = if (processing.callId == call.id && processing.chunksTotal > 0)
                        "מתמלל… ${processing.chunksDone}/${processing.chunksTotal}" else "מתמלל…"
                    Text(label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
                }
                CallStatus.RECORDED -> Text("ממתין לתמלול", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                CallStatus.TRANSCRIBE_FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("התמלול נכשל${call.error?.let { ": $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onTranscribe) { Text("נסה שוב") }
                }
            }
            if (expanded && call.status == CallStatus.TRANSCRIBED) {
                transcript()
                TextButton(onClick = onTranscribe) { Text("תמלל מחדש") }
            }

            // Diagnostics: what the prototype is here to learn about each device.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onTrace != null) AssistChip(onClick = onTrace, label = {
                    Text("יומן אבחון", style = MaterialTheme.typography.labelSmall)
                })
                when {
                    failed -> Chip("ההקלטה נכשלה", warn = true)
                    quiet -> Chip("התקבל שקט", warn = true)
                }
                if (call.speakerResult != SpeakerResult.NOT_APPLICABLE) {
                    Chip(speakerLabel(call), warn = call.speakerResult == SpeakerResult.FAILED)
                }
                call.audioSource?.let { Chip(if (it == "mic") "מקור: MIC" else "מקור: זיהוי דיבור") }
                if (!failed && !call.foreground) Chip("ללא שירות חזית", warn = true)
            }
        }
    }
}

/** Seek bar for the loaded recording; drag to scrub, release to jump. */
@Composable
private fun SeekBar(player: AudioPlayer) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationMs.toFloat()
    val shown = dragging ?: player.positionMs.toFloat()

    // Media timelines read left-to-right even in Hebrew.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.padding(end = 8.dp)) {
            Slider(
                value = shown.coerceIn(0f, duration),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo(it.toInt()) }
                    dragging = null
                },
                valueRange = 0f..duration,
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(shown.toLong()), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(player.durationMs.toLong()), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Chip(text: String, warn: Boolean = false) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (warn) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
    )
}

private fun speakerLabel(call: CallEntity): String = when (call.speakerResult) {
    SpeakerResult.ON -> when (call.speakerMethod) {
        "api" -> "רמקול: הופעל אוטומטית"
        "tap" -> "רמקול: הופעל בלחיצה"
        "user" -> "רמקול: הופעל ידנית"
        else -> "רמקול: פעיל"
    }
    SpeakerResult.SKIPPED_HEADSET -> "אוזניות מחוברות"
    SpeakerResult.DISABLED -> "רמקול אוטומטי כבוי"
    else -> "רמקול: לא הופעל"
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("he")).format(Date(millis))

private fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun share(context: Context, path: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
    val send = Intent(Intent.ACTION_SEND)
        .setType("audio/wav")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "שיתוף הקלטה").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
