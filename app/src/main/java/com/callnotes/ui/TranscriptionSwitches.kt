package com.callnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.processing.ProcessingStatus
import com.callnotes.util.Prefs

/** Auto-transcribe switch and the outcome of the last transcription run. */
@Composable
fun TranscriptionSwitches(prefs: Prefs) {
    var auto by remember { mutableStateOf(prefs.autoTranscribe) }
    val noteVersion by ProcessingStatus.version.collectAsStateWithLifecycle()
    val lastRun = remember(noteVersion) { prefs.lastProcessing }
    Panel(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("תמלול אוטומטי", style = MaterialTheme.typography.bodyLarge)
                Text("כל הקלטה מתומללת ברקע בסיומה.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = auto, onCheckedChange = { auto = it; prefs.autoTranscribe = it })
        }
        lastRun?.let {
            Text("הרצה אחרונה: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
