package com.callnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.callnotes.CallNotesApp
import com.callnotes.capture.CallRecorderService
import com.callnotes.util.Prefs

/** Settings tab: arranges the sections in the new layout. */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, resumeTick: Int, onModelReady: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val prefs = remember { Prefs(context) }
    var tick by remember { mutableIntStateOf(0) }
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
                    onAutostart = { SetupChecks.startXiaomiAutostart(context) },
                )
            }
        }
        item { SectionHeader("תמלול") }
        item { ModelSection(app.models, prefs, onModelReady) }
        item { TranscriptionSwitches(prefs) }
        item { SectionHeader("כללי") }
        item { SettingsSection(prefs) }
        if (!setup.callLogOk || !setup.notificationsOk) {
            item { SectionHeader("תוספות") }
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
                    onAutostart = { SetupChecks.startXiaomiAutostart(context) },
                )
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
}
