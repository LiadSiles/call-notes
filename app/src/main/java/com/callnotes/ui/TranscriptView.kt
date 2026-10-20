package com.callnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.data.CallDao

/** The call's transcript; tapping a line plays the recording from that point. */
@Composable
fun TranscriptView(dao: CallDao, callId: Long, onSeek: (Long) -> Unit) {
    val flow = remember(callId) { dao.observeSegments(callId) }
    val segments by flow.collectAsStateWithLifecycle(emptyList())

    Column(Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp, bottom = 4.dp)) {
        if (segments.isEmpty()) {
            Text("לא זוהה דיבור בהקלטה.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        segments.forEach { seg ->
            Row(
                Modifier.fillMaxWidth().clickable { onSeek(seg.startMs) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(formatClock(seg.startMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(seg.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

internal fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
