package com.callnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.callnotes.data.CallEntity
import com.callnotes.data.CallKind
import com.callnotes.data.CallListItem
import com.callnotes.data.CallStatus
import com.callnotes.data.TaskEntity
import com.callnotes.data.TaskListItem
import com.callnotes.processing.ProcessingStatus
import com.callnotes.ui.theme.CallNotesTheme
import com.callnotes.util.Prefs
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main layouts with sample data to PNGs (build/screenshots), so design changes
 * can be reviewed without a phone. Run with -Pscreenshots; excluded from the normal build.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            CallNotesTheme(dark = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private val hub = HubState(
        ready = true, missingSteps = 0,
        recording = "פעילה · כולל שיחות אינטרנט",
        transcription = "Whisper עברית (ivrit.ai) · מאוזן · אוטומטי",
        speakers = "דגימת הקול שלך שמורה",
        summaries = "Gemma 4 E4B · תזכורות",
        battery = "רק בטעינה",
        privacy = "נעילה · הקלטות נמחקות אחרי חודש",
        setup = "הכל מוגדר",
        about = "גרסה 0.10.0",
    )

    @Test fun settingsHub() = shot("settings_hub") { SettingsHubContent(PaddingValues(0.dp), hub) {} }

    @Test fun settingsHubDark() = shot("settings_hub_dark", dark = true) { SettingsHubContent(PaddingValues(0.dp), hub) {} }

    @Test fun settingsHubIncomplete() = shot("settings_hub_incomplete") {
        SettingsHubContent(PaddingValues(0.dp), hub.copy(ready = false, missingSteps = 2, setup = "חסרים 2 שלבים")) {}
    }

    @Test fun settingsSubPage() = shot("settings_battery") {
        val prefs = Prefs(LocalContext.current)
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            item { SubPageBar("הקלטה וסוללה", onBack = {}) }
            item { GroupLabel("שיחות") }
            item { RecordingSettings(prefs) }
            item { GroupLabel("מתי לעבד") }
            item { BatterySettings(prefs) }
        }
    }

    @Test fun settingsProcessing() = shot("settings_processing") {
        val prefs = Prefs(LocalContext.current).apply { autoMaxMinutes = 30 }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            item { SubPageBar("תמלול", onBack = {}) }
            item { GroupLabel("עיבוד") }
            item { TranscriptionSwitches(prefs) }
        }
    }

    private fun call(
        id: Long, name: String?, minutesAgo: Long, dur: Long,
        status: String = CallStatus.TRANSCRIBED, kind: String = CallKind.CELLULAR,
    ) = CallEntity(
        id = id, startedAt = NOW - minutesAgo * 60_000, endedAt = NOW - minutesAgo * 60_000 + dur * 1000,
        incoming = id % 2 == 0L, number = "050-1234567", contactName = name, filePath = "/x.wav", audioSource = "mic",
        peakLevel = 20_000, silenced = false, speakerResult = "na", speakerMethod = null, status = status, kind = kind,
    )

    private val calls = listOf(
        CallListItem(call(1, "דני כהן", 30, 412), "סוכם לשלוח את הצעת המחיר עד יום שלישי ולתאם פגישה עם הספק."),
        CallListItem(call(2, "מיכל", 95, 125, status = CallStatus.TRANSCRIBING), null),
        CallListItem(call(3, null, 1500, 1800, kind = CallKind.VOIP), "שיחת עבודה על לוחות הזמנים של הפרויקט והחלוקה לשלבים."),
        CallListItem(call(4, "אמא", 1600, 300, status = CallStatus.RECORDED), null),
    )

    @Test fun callsList() = shot("calls") {
        val processing = ProcessingStatus.State(running = true, callId = 2, progress = 40, progressMax = 100, phase = "transcribing")
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            item { CallsHeader("ההקלטה פעילה" to Tone.Good, canSearch = true) {} }
            calls.groupBy { dayLabel(it.call.startedAt) }.forEach { (day, list) ->
                item {
                    Column {
                        GroupLabel(day, Modifier.padding(top = 4.dp))
                        CardGroup {
                            list.forEachIndexed { i, c ->
                                if (i > 0) RowDivider()
                                CallRow(c, processing) {}
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun tasks() = shot("tasks") {
        val items = listOf(
            TaskEntity(id = 1, callId = 1, idx = 0, title = "לשלוח לדני את הצעת המחיר", owner = "me", dueDate = "2026-10-27",
                duePhrase = "עד יום שלישי", quote = null),
            TaskEntity(id = 2, callId = 1, idx = 1, title = "לתאם פגישה עם הספק", owner = "other", dueDate = null,
                duePhrase = null, quote = null, certain = false),
        ).map { TaskListItem(it, NOW - 1_800_000, "דני כהן", "050-1234567", CallKind.CELLULAR, true) }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            item { PageHeader("משימות", subtitle = "2 פתוחות · מהשיחות שלך") {} }
            items.forEach { t -> item { TaskCard(t, emptyList(), false, {}, {}, {}) } }
        }
    }

    private companion object {
        val NOW = System.currentTimeMillis()
    }
}
