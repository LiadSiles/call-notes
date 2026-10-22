package com.callnotes.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.CallNotesApp
import com.callnotes.capture.CallRecorderService
import com.callnotes.capture.ServiceStatus
import com.callnotes.inference.ModelRole
import com.callnotes.inference.speakers.VoiceProfile
import com.callnotes.inference.whisper.WhisperSpeed
import com.callnotes.processing.ProcessingStatus
import com.callnotes.ui.theme.LocalTints
import com.callnotes.util.Prefs

/** The settings pages; the hub shows one row per page with what's set there now. */
enum class SettingsPage(val title: String) {
    RECORDING("הקלטה"),
    TRANSCRIPTION("תמלול"),
    SPEAKERS("מי אמר מה"),
    SUMMARIES("סיכומים ומשימות"),
    BATTERY("סוללה"),
    PRIVACY("פרטיות ואבטחה"),
    SETUP("הרשאות והגדרה"),
    ABOUT("אודות"),
}

/** One line per hub row: the state that matters, so most visits don't need to open the page. */
data class HubState(
    val ready: Boolean,
    val missingSteps: Int,
    val recording: String,
    val transcription: String,
    val speakers: String,
    val summaries: String,
    val battery: String,
    val privacy: String,
    val setup: String,
    val about: String,
)

/** Settings tab: a calm hub of categories; each opens its own page. */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, resumeTick: Int, onModelReady: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val opening = targetState != null
            val dir = if (opening) 1 else -1
            (slideInHorizontally(tween(240)) { w -> -dir * w / 6 } + fadeIn(tween(200)))
                .togetherWith(slideOutHorizontally(tween(240)) { w -> dir * w / 10 } + fadeOut(tween(140)))
        },
        label = "settings",
    ) { current ->
        if (current == null) SettingsHub(contentPadding, resumeTick, onOpen = { page = it })
        else SettingsSubPage(current, contentPadding, resumeTick, onModelReady, onBack = { page = null })
    }
}

@Composable
private fun SettingsHub(contentPadding: PaddingValues, resumeTick: Int, onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val prefs = remember { Prefs(context) }
    val setup = rememberSetupState(resumeTick)
    val service by ServiceStatus.state.collectAsStateWithLifecycle()
    val models by app.models.state.collectAsStateWithLifecycle()
    val noteVersion by ProcessingStatus.version.collectAsStateWithLifecycle()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    val state = remember(resumeTick, noteVersion, models, service, setup) {
        val missing = listOf(setup.permissionsOk, setup.a11yOn, setup.batteryOk).count { !it }
        val transcribe = models.active(ModelRole.TRANSCRIBE)
        val analyze = models.active(ModelRole.ANALYZE)
        HubState(
            ready = setup.requiredOk && service.connected && service.monitoring && prefs.recordingEnabled,
            missingSteps = missing,
            recording = if (!prefs.recordingEnabled) "כבויה" else listOfNotNull(
                "פעילה", "כולל שיחות אינטרנט".takeIf { prefs.recordVoip }, "רמקול אוטומטי".takeIf { prefs.autoSpeaker },
            ).joinToString(" · "),
            transcription = if (transcribe == null) "לא הותקן מודל" else listOf(
                transcribe.displayName, WhisperSpeed.current(prefs).label, if (prefs.autoTranscribe) "אוטומטי" else "ידני",
            ).joinToString(" · "),
            speakers = if (VoiceProfile.exists(context)) "דגימת הקול שלך שמורה" else "בלי דגימת קול",
            summaries = listOfNotNull(analyze?.displayName ?: "לא הותקן מודל", "תזכורות".takeIf { prefs.taskReminders })
                .joinToString(" · "),
            battery = listOfNotNull(
                "רק בטעינה".takeIf { prefs.onlyWhileCharging }, "נעצר בחיסכון סוללה".takeIf { prefs.pauseInBatterySaver },
            ).joinToString(" · ").ifEmpty { "ללא הגבלה" },
            privacy = listOfNotNull(
                "נעילה".takeIf { prefs.appLock },
                "הצפנה נוספת".takeIf { prefs.encryptData },
                prefs.audioRetentionDays.takeIf { it > 0 }?.let { "הקלטות נמחקות אחרי ${daysLabel(it)}" },
                prefs.callRetentionDays.takeIf { it > 0 }?.let { "שיחות נמחקות אחרי ${daysLabel(it)}" },
                "דחיסה".takeIf { prefs.compressRecordings },
            ).joinToString(" · ").ifEmpty { "הכל נשמר בטלפון בלבד" },
            setup = if (missing == 0) "הכל מוגדר" else if (missing == 1) "חסר שלב אחד" else "חסרים $missing שלבים",
            about = "גרסה ${version ?: "?"}",
        )
    }
    SettingsHubContent(contentPadding, state, onOpen)
}

/** Stateless hub layout (also used by the screenshot tests). */
@Composable
fun SettingsHubContent(contentPadding: PaddingValues, s: HubState, onOpen: (SettingsPage) -> Unit) {
    val t = LocalTints.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
    ) {
        item { PageHeader("הגדרות") }
        item {
            if (s.ready) HeroCard(AppIcons.ShieldCheck, "ההקלטה פעילה", "שיחות מוקלטות ומעובדות על הטלפון בלבד.", good = true)
            else HeroCard(
                Icons.Filled.Warning,
                "ההגדרה לא הושלמה",
                if (s.missingSteps > 0) "${s.setup} — בלעדיהם שיחות לא יוקלטו." else "ההקלטה כבויה או שהשירות לא פועל.",
                good = false,
                onClick = { onOpen(if (s.missingSteps > 0) SettingsPage.SETUP else SettingsPage.RECORDING) },
            )
        }
        item { GroupLabel("שיחות") }
        item {
            CardGroup {
                NavRow(Icons.Filled.Call, t.green, SettingsPage.RECORDING.title, s.recording, { onOpen(SettingsPage.RECORDING) })
                RowDivider()
                NavRow(AppIcons.Waves, t.indigo, SettingsPage.TRANSCRIPTION.title, s.transcription, { onOpen(SettingsPage.TRANSCRIPTION) })
                RowDivider()
                NavRow(AppIcons.People, t.teal, SettingsPage.SPEAKERS.title, s.speakers, { onOpen(SettingsPage.SPEAKERS) })
                RowDivider()
                NavRow(AppIcons.Sparkle, t.plum, SettingsPage.SUMMARIES.title, s.summaries, { onOpen(SettingsPage.SUMMARIES) })
            }
        }
        item { GroupLabel("הטלפון") }
        item {
            CardGroup {
                NavRow(AppIcons.Battery, t.brass, SettingsPage.BATTERY.title, s.battery, { onOpen(SettingsPage.BATTERY) })
                RowDivider()
                NavRow(Icons.Filled.Lock, t.rose, SettingsPage.PRIVACY.title, s.privacy, { onOpen(SettingsPage.PRIVACY) })
                RowDivider()
                NavRow(AppIcons.Shield, t.slate, SettingsPage.SETUP.title, s.setup, { onOpen(SettingsPage.SETUP) },
                    attention = s.missingSteps > 0)
            }
        }
        item { GroupLabel("") }
        item {
            CardGroup {
                NavRow(Icons.Filled.Info, t.slate, SettingsPage.ABOUT.title, s.about, { onOpen(SettingsPage.ABOUT) })
            }
        }
        item { Note("הקלטות, תמלולים וסיכומים לא יוצאים מהטלפון. הרשת משמשת רק להורדת המודלים.", Modifier.padding(top = 12.dp)) }
    }
}

@Composable
private fun SettingsSubPage(
    page: SettingsPage,
    contentPadding: PaddingValues,
    resumeTick: Int,
    onModelReady: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val prefs = remember { Prefs(context) }
    var tick by remember { mutableIntStateOf(0) }
    var resetKey by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    val setup = rememberSetupState(resumeTick + tick)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        CallRecorderService.refreshFromUi()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
    ) {
        item { SubPageBar(page.title, onBack) }
        when (page) {
            SettingsPage.RECORDING -> {
                item { GroupLabel("שיחות") }
                item { key(resetKey) { RecordingSettings(prefs) } }
            }
            SettingsPage.TRANSCRIPTION -> {
                item { GroupLabel("מודל") }
                item { ModelSection(app.models, prefs, ModelRole.TRANSCRIBE, onModelReady) }
                item { key(resetKey) { SpeedSection(app.models, prefs) } }
                item { GroupLabel("עיבוד") }
                item { key(resetKey) { TranscriptionSwitches(prefs) } }
            }
            SettingsPage.SPEAKERS -> {
                item { GroupLabel("זיהוי דוברים") }
                item { SpeakerSection(prefs) }
            }
            SettingsPage.SUMMARIES -> {
                item { GroupLabel("מודל") }
                item { ModelSection(app.models, prefs, ModelRole.ANALYZE) }
                item { Note("מודל שפה שקורא את השיחה הכתובה ומכין כותרת, סיכום ומשימות — על הטלפון, אחרי התמלול.") }
                item { GroupLabel("משימות") }
                item { key(resetKey) { ReminderSettings(prefs) } }
            }
            SettingsPage.BATTERY -> {
                item { GroupLabel("מתי לעבד") }
                item { key(resetKey) { BatterySettings(prefs) } }
            }
            SettingsPage.PRIVACY -> privacyItems(prefs, resetKey)
            SettingsPage.SETUP -> {
                item { GroupLabel("נדרש") }
                item {
                    SetupSection(
                        setup = setup,
                        onPermissions = { launcher.launch(SetupChecks.requiredPermissions) },
                        onAccessibility = { context.startActivity(SetupChecks.accessibilitySettings()) },
                        onAppDetails = { context.startActivity(SetupChecks.appDetails(context)) },
                        onBattery = { context.startActivity(SetupChecks.batteryRequest(context)) },
                        onBatteryUndo = { SetupChecks.openBatterySettings(context) },
                        onAutostart = { SetupChecks.startXiaomiAutostart(context) },
                    )
                }
                item { GroupLabel("רשות") }
                item {
                    OptionalSection(
                        setup = setup,
                        onCallLog = { launcher.launch(arrayOf(SetupChecks.CALL_LOG)) },
                        onNotifications = { launcher.launch(SetupChecks.notificationPermission) },
                        onAppDetails = { context.startActivity(SetupChecks.appDetails(context)) },
                    )
                }
            }
            SettingsPage.ABOUT -> {
                item { GroupLabel("CallNotes") }
                item {
                    val version = remember {
                        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                    }
                    CardGroup {
                        InfoRow("גרסה", version ?: "?")
                        RowDivider(inset = false)
                        InfoRow("פרטיות", "הקלטות, תמלולים, סיכומים ומשימות נשמרים ומעובדים בטלפון בלבד. הרשת משמשת רק להורדת המודלים.")
                        RowDivider(inset = false)
                        InfoRow("גופן", "Heebo · SIL Open Font License")
                    }
                }
                item { GroupLabel("איפוס") }
                item {
                    CardGroup {
                        InfoRow("שחזור הגדרות ברירת מחדל", "המתגים חוזרים להתחלה. הקלטות, תמלולים, מודלים והרשאות לא משתנים.",
                            onClick = { confirmReset = true })
                    }
                }
            }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("לשחזר את ברירות המחדל?") },
        text = {
            Text(
                "המתגים יחזרו למצב ההתחלתי: הקלטת שיחות פעילה, שיחות אינטרנט פעילות, רמקול אוטומטי כבוי, " +
                    "תמלול אוטומטי פעיל, הורדת מודל ב-Wi-Fi בלבד, וללא מחיקה אוטומטית.\n\n" +
                    "ההקלטות, התמלולים, המודל, הנעילה, ההצפנה וההרשאות לא משתנים."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.resetSwitches()
                ProcessingStatus.touch()
                resetKey++
                confirmReset = false
            }) { Text("שחזור") }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("ביטול") } },
    )
}

private fun LazyListScope.privacyItems(prefs: Prefs, resetKey: Int) {
    item { GroupLabel("גישה") }
    item { LockSection(prefs) }
    item { GroupLabel("שמירה ומחיקה") }
    item { key(resetKey) { RetentionSection(prefs) } }
    item { GroupLabel("אחסון") }
    item { key(resetKey) { CompressionSection(prefs) } }
    item { GroupLabel("הצפנה") }
    item { EncryptionSection(prefs) }
}
