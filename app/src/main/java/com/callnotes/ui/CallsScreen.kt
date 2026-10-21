package com.callnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.CallNotesApp
import com.callnotes.capture.ServiceStatus
import com.callnotes.data.CallListItem
import com.callnotes.processing.ProcessingStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    contentPadding: PaddingValues,
    resumeTick: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpen: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val loaded by app.callList.collectAsStateWithLifecycle()
    val items = loaded.orEmpty()
    val service by ServiceStatus.state.collectAsStateWithLifecycle()
    val processing by ProcessingStatus.state.collectAsStateWithLifecycle()
    val models by app.models.state.collectAsStateWithLifecycle()
    val setup = rememberSetupState(resumeTick)
    val ready = setup.requiredOk && service.connected && service.monitoring

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("שיחות", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    when {
                        service.recordingNow -> Pill("מקליט עכשיו", Tone.Busy)
                        ready -> Pill("ההקלטה פעילה", Tone.Good)
                        else -> Pill("דרושה הגדרה", Tone.Bad)
                    }
                }
            }

            if (!ready) item {
                Banner(
                    title = "ההקלטה עדיין לא פעילה",
                    body = "חסרים שלבים בהגדרה. בלעדיהם שיחות לא יוקלטו.",
                    action = "להגדרות",
                    onAction = onOpenSettings,
                    warn = true,
                )
            }
            if (ready && !models.ready && models.download is com.callnotes.inference.ModelManager.Download.Idle) item {
                Banner(
                    title = "הורד מודל כדי לתמלל",
                    body = "ההקלטות נשמרות, אבל לא יתומללו עד שיותקן מודל.",
                    action = "להגדרות",
                    onAction = onOpenSettings,
                )
            }
            if (processing.running) item {
                val label = when (processing.phase) {
                    "loading" -> "טוען את מודל התמלול…"
                    "waiting_thermal" -> "הטלפון חם — התמלול ממתין שיתקרר"
                    else -> "מתמלל שיחה · מקטע ${processing.chunksDone} מתוך ${processing.chunksTotal}"
                }
                Panel {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.padding(4.dp))
                    if (processing.chunksTotal > 0) {
                        LinearProgressIndicator(
                            progress = { processing.chunksDone.toFloat() / processing.chunksTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }

            // null = still loading: show nothing rather than flashing the empty state.
            if (loaded != null && items.isEmpty()) item {
                EmptyState(
                    icon = Icons.Filled.Call,
                    title = "אין עדיין שיחות",
                    body = "כל שיחה תופיע כאן בסיומה. אפשר גם לייבא קובץ אודיו כדי לנסות את התמלול.",
                )
            }

            var lastDay: String? = null
            items.forEach { entry ->
                val day = dayLabel(entry.call.startedAt)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day_$day") { SectionHeader(day) }
                }
                item(key = entry.call.id) {
                    CallRow(entry, processing, onClick = { onOpen(entry.call.id) })
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onImport,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("ייבוא אודיו") },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
    }
}

@Composable
private fun CallRow(item: CallListItem, processing: ProcessingStatus.State, onClick: () -> Unit) {
    val call = item.call
    val live = processing.callId == call.id
    val status = statusOf(call, if (live) processing.chunksDone else null, if (live) processing.chunksTotal else null)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Avatar(call)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName(call), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatTime(call.startedAt), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${kindLabel(call)} · ${formatDuration(call.endedAt - call.startedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.preview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                if (status != null && status.second != Tone.Good) {
                    Pill(status.first, status.second, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Banner(title: String, body: String, action: String, onAction: () -> Unit, warn: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (warn) cs.errorContainer else cs.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
            if (warn) {
                Icon(Icons.Filled.Warning, null, tint = cs.onErrorContainer, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = if (warn) cs.onErrorContainer else cs.onPrimaryContainer)
                Text(body, style = MaterialTheme.typography.bodySmall,
                    color = if (warn) cs.onErrorContainer else cs.onPrimaryContainer)
                TextButton(onClick = onAction, modifier = Modifier.padding(top = 2.dp)) { Text(action) }
            }
        }
    }
}
