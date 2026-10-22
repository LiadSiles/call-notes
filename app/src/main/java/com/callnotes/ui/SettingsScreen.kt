package com.callnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.callnotes.CallNotesApp
import com.callnotes.capture.CallRecorderService
import com.callnotes.inference.ModelRole
import com.callnotes.util.Prefs

/** Settings tab: arranges the sections in the new layout. */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, resumeTick: Int, onModelReady: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val prefs = remember { Prefs(context) }
    var tick by remember { mutableIntStateOf(0) }
    // Bumped by "restore defaults" so every switch re-reads its saved value.
    var resetKey by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    val setup = rememberSetupState(resumeTick + tick)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        CallRecorderService.refreshFromUi()
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
    ) {
        item {
            Text("הגדרות", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
        }
        if (!setup.requiredOk || !setup.batteryOk) {
            item { SectionHeader("השלמת ההגדרה") }
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
        }
        item { SectionHeader("תמלול") }
        item { key(resetKey) { ModelSection(app.models, prefs, ModelRole.TRANSCRIBE, onModelReady) } }
        item { key(resetKey) { SpeedSection(app.models, prefs) } }
        item { key(resetKey) { TranscriptionSwitches(prefs) } }
        item { SectionHeader("מי אמר מה") }
        item { key(resetKey) { SpeakerSection(prefs) } }
        item { SectionHeader("סיכומים ומשימות") }
        item {
            Text(
                "מודל שפה שקורא את השיחה הכתובה ומכין סיכום ומשימות, על הטלפון. רץ אחרי התמלול; לא נדרש לתמלול עצמו.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        item { key(resetKey) { ModelSection(app.models, prefs, ModelRole.ANALYZE) } }
        item { SectionHeader("פרטיות") }
        item { key(resetKey) { RetentionSection(prefs) } }
        item { LockSection(prefs) }
        item { key(resetKey) { CompressionSection(prefs) } }
        item { SectionHeader("כללי") }
        item { key(resetKey) { SettingsSection(prefs) } }
        run {
            item { SectionHeader("הרשאות נוספות") }
            item {
                OptionalSection(
                    setup = setup,
                    onCallLog = { launcher.launch(arrayOf(SetupChecks.CALL_LOG)) },
                    onNotifications = { launcher.launch(SetupChecks.notificationPermission) },
                    onAppDetails = { context.startActivity(SetupChecks.appDetails(context)) },
                )
            }
        }
        if (setup.requiredOk && setup.batteryOk) {
            item { SectionHeader("מצב ההגדרה") }
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
        }
        item {
            OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.padding(top = 24.dp)) {
                Text("שחזור הגדרות ברירת מחדל")
            }
        }
        item {
            Text(
                "גרסה ${version ?: "?"} · כל ההקלטות, התמלולים והמודל נשמרים על הטלפון בלבד.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp, start = 4.dp),
            )
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("לשחזר את ברירות המחדל?") },
        text = {
            Text(
                "המתגים יחזרו למצב ההתחלתי: הקלטת שיחות פעילה, שיחות אינטרנט פעילות, רמקול אוטומטי כבוי, " +
                    "תמלול אוטומטי פעיל והורדת מודל ב-Wi-Fi בלבד.\n\n" +
                    "ההקלטות, התמלולים, המודל וההרשאות לא משתנים."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.resetSwitches()
                com.callnotes.processing.ProcessingStatus.touch()
                resetKey++
                confirmReset = false
            }) { Text("שחזור") }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("ביטול") } },
    )
}
