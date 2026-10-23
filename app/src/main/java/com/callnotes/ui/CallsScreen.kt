package com.callnotes.ui

import com.callnotes.processing.Phase
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    /** Opens a call at a transcript line (from search). */
    onOpenAt: (callId: Long, segment: Int?, text: String?) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val loaded by app.callList.collectAsStateWithLifecycle()
    val items = loaded.orEmpty()
    val service by ServiceStatus.state.collectAsStateWithLifecycle()
    val processing by ProcessingStatus.state.collectAsStateWithLifecycle()
    val summaryState by ProcessingStatus.summary.collectAsStateWithLifecycle()
    val models by app.models.state.collectAsStateWithLifecycle()
    val setup = rememberSetupState(resumeTick)
    val prefs = remember { com.callnotes.util.Prefs(context) }
    val noteVersion by ProcessingStatus.version.collectAsStateWithLifecycle()
    var autoOn by remember(noteVersion, resumeTick) { mutableStateOf(prefs.autoTranscribe) }
    val limitMinutes = remember(noteVersion, resumeTick) { prefs.autoMaxMinutes }
    /** Over the length limit: transcribed and summarised only by hand. */
    val manualOnly: (com.callnotes.data.CallEntity) -> Boolean = { c ->
        limitMinutes > 0 && c.endedAt - c.startedAt > limitMinutes * 60_000L
    }
    val waiting = items.count { it.call.status == com.callnotes.data.CallStatus.RECORDED && !manualOnly(it.call) }
    val saverPaused = remember(resumeTick, noteVersion) { com.callnotes.util.BatterySaver.paused(context) }
    // Automatic steps held back until the phone is plugged in, and how much waits for each.
    val transcribeWaits = remember(resumeTick, noteVersion, processing.running) {
        prefs.autoTranscribe && com.callnotes.util.Energy.transcribeWaitsForCharger(context)
    }
    val summaryWaits = remember(resumeTick, noteVersion, summaryState.running) {
        prefs.autoSummarize && com.callnotes.util.Energy.summaryWaitsForCharger(context)
    }
    val unsummarised = items.count {
        it.call.status == com.callnotes.data.CallStatus.TRANSCRIBED && it.call.analysisStatus == null && !manualOnly(it.call)
    }
    val ready = setup.requiredOk && service.connected && service.monitoring
    var searching by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<com.callnotes.data.SearchHit>?>(null) }
    androidx.compose.runtime.LaunchedEffect(query, searching) {
        if (!searching) return@LaunchedEffect
        kotlinx.coroutines.delay(250) // typing
        hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Search.run(app.database.calls(), query) }
    }
    androidx.activity.compose.BackHandler(enabled = searching) { searching = false; query = "" }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (searching) {
                item {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("חיפוש בשיחות") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            androidx.compose.material3.IconButton(onClick = { searching = false; query = "" }) {
                                Icon(Icons.Filled.Close, "סגירת החיפוש")
                            }
                        },
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp).focusRequester(focus),
                    )
                }
                searchResults(query, hits, onOpenAt)
                return@LazyColumn
            }
            item {
                CallsHeader(
                    status = when {
                        service.recordingNow -> "מקליט עכשיו" to Tone.Busy
                        ready -> "ההקלטה פעילה" to Tone.Good
                        else -> "דרושה השלמת הגדרה" to Tone.Bad
                    },
                    canSearch = items.isNotEmpty(),
                    onSearch = { searching = true; hits = null },
                )
            }

            if (saverPaused) item {
                Banner(
                    title = "מושהה — מצב חיסכון בסוללה",
                    body = "לפי ההגדרה שלך, האפליקציה לא מקליטה ולא מעבדת כל עוד חיסכון הסוללה פעיל.",
                    action = "להגדרות",
                    onAction = onOpenSettings,
                    warn = true,
                )
            }
            val chargerLines = listOfNotNull(
                (if (waiting == 1) "הקלטה אחת תתומלל" else "$waiting הקלטות יתומללו")
                    .takeIf { transcribeWaits && waiting > 0 && !processing.running },
                (if (unsummarised == 1) "שיחה אחת תסוכם" else "$unsummarised שיחות יסוכמו")
                    .takeIf { summaryWaits && unsummarised > 0 && !summaryState.running },
            )
            if (chargerLines.isNotEmpty() && !saverPaused) item {
                Banner(
                    title = "ממתין לטעינה",
                    body = chargerLines.joinToString(", ") + " כשהטלפון יחובר למטען. " +
                        "אפשר לתמלל או לסכם שיחה מסוימת עכשיו מתוך השיחה עצמה.",
                    action = "להגדרות",
                    onAction = onOpenSettings,
                )
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
            if (!processing.running && !summaryState.running && !autoOn && waiting > 0 && models.ready) item {
                Banner(
                    title = "התמלול האוטומטי כבוי",
                    body = if (waiting == 1) "הקלטה אחת ממתינה לתמלול." else "$waiting הקלטות ממתינות לתמלול.",
                    action = "הפעלה מחדש",
                    onAction = {
                        prefs.autoTranscribe = true
                        autoOn = true
                        com.callnotes.processing.TranscriptionService.start(context)
                    },
                )
            }
            // Transcription and summaries run side by side: one row each, with the call's name.
            val workRows = listOf(processing, summaryState).filter { it.running && it.phase != "" || it.callId != null }
            if (workRows.isNotEmpty()) item {
                Panel {
                    workRows.forEachIndexed { i, st ->
                        if (i > 0) Spacer(Modifier.padding(6.dp))
                        val who = st.callId?.let { id -> items.firstOrNull { it.call.id == id }?.call }?.let { displayName(it) }
                        val label = when (st.phase) {
                            Phase.SPEAKERS -> "מזהה מי אמר מה"
                            Phase.LOADING -> "טוען את מודל התמלול"
                            Phase.PREPARING_MODEL -> "מכין גרסה מהירה של המודל · ${if (st.progressMax > 0) st.progress * 100 / st.progressMax else 0}%"
                            Phase.LOADING_ANALYZER -> "טוען את מודל הסיכום"
                            Phase.ANALYZING -> if (st.progressMax > 1) "מסכם · חלק ${st.progress} מתוך ${st.progressMax}" else "מסכם"
                            Phase.WAITING_MEMORY -> "ממתין לזיכרון כדי לסכם"
                            Phase.WAITING_THERMAL -> "הטלפון חם — ממתין שיתקרר"
                            Phase.MEMO_TRANSCRIBING -> "מתמלל הקלטת משימה"
                            Phase.MEMO_TASK -> "מכין משימה מההקלטה"
                            else -> if (st.progressMax > 0) "מתמלל · ${st.progress * 100 / st.progressMax}%" else "מתמלל"
                        } + (who?.let { " · $it" } ?: "")
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(2.dp))
                        if (st.progressMax > 0 && st.phase == Phase.TRANSCRIBING) {
                            LinearProgressIndicator(
                                progress = { st.progress.toFloat() / st.progressMax },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    TextButton(onClick = {
                        com.callnotes.processing.TranscriptionService.stopAll(context)
                        autoOn = false
                    }) { Text("עצירת הכל") }
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

            // One card per day, calls as rows inside it.
            items.groupBy { dayLabel(it.call.startedAt) }.forEach { (day, calls) ->
                item(key = "day_$day") {
                    Column {
                        GroupLabel(day, Modifier.padding(top = 4.dp))
                        CardGroup {
                            calls.forEachIndexed { i, entry ->
                                if (i > 0) RowDivider(inset = true)
                                CallRow(entry, processing, summaryState, manualOnly = manualOnly(entry.call), onClick = { onOpen(entry.call.id) })
                            }
                        }
                    }
                }
            }
        }

        if (!searching) ExtendedFloatingActionButton(
            onClick = onImport,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("ייבוא אודיו") },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
    }
}

/** Page title, recording status and the search button. */
@Composable
internal fun CallsHeader(status: Pair<String, Tone>, canSearch: Boolean, onSearch: () -> Unit) {
    PageHeader("שיחות") {
        Pill(status.first, status.second)
        if (canSearch) {
            Spacer(Modifier.width(6.dp))
            androidx.compose.material3.FilledTonalIconButton(
                onClick = onSearch,
                colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) { Icon(Icons.Filled.Search, "חיפוש") }
        }
    }
}

@Composable
internal fun CallRow(
    item: CallListItem,
    processing: ProcessingStatus.State,
    summary: ProcessingStatus.State,
    manualOnly: Boolean = false,
    onClick: () -> Unit,
) {
    val call = item.call
    val stage = callStage(call, processing, summary, manualOnly)
    Surface(
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Avatar(call, size = 40)
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
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                // Always shown: transcribed / summarised / not yet, at a glance.
                Pill(stage.first, stage.second, Modifier.padding(top = 8.dp))
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
