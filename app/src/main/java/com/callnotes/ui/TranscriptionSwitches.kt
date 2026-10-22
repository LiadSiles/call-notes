package com.callnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.processing.ProcessingLog
import com.callnotes.processing.ProcessingStatus
import com.callnotes.util.Prefs

/** Auto-transcribe switch and the outcome of the last transcription run. */
@Composable
fun TranscriptionSwitches(prefs: Prefs) {
    val noteVersion by ProcessingStatus.version.collectAsStateWithLifecycle()
    // Re-read on every note: "stop" turns the switch off from elsewhere.
    var auto by remember(noteVersion) { mutableStateOf(prefs.autoTranscribe) }
    val lastRun = remember(noteVersion) { prefs.lastProcessing }
    var showLog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    CardGroup {
        SwitchRow("תמלול וסיכום אוטומטיים", "כל הקלטה מעובדת ברקע בסיומה.", auto) { auto = it; prefs.autoTranscribe = it }
        RowDivider(inset = false)
        InfoRow("יומן עיבוד", lastRun?.let { "הרצה אחרונה: $it" } ?: "העיבוד עוד לא רץ.", onClick = { showLog = true }) {
            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.outline)
        }
    }
    if (showLog) TraceDialog(ProcessingLog.file(context), onDismiss = { showLog = false }, title = "יומן תמלול")
}
