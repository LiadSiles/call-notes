package com.callnotes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.CallNotesApp
import com.callnotes.capture.CallTrace
import com.callnotes.data.CallEntity
import com.callnotes.data.CallStatus
import com.callnotes.data.SegmentEntity
import com.callnotes.data.SpeakerResult
import com.callnotes.processing.ProcessingLog
import com.callnotes.processing.ProcessingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Below this peak level (~-40 dBFS) the recording is effectively silent. */
private const val SILENT_PEAK = 330

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailScreen(
    callId: Long,
    player: AudioPlayer,
    onBack: () -> Unit,
    onTranscribe: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val dao = app.database.calls()
    val scope = rememberCoroutineScope()
    // Start from the row we already have, so the screen never opens blank.
    val initial = remember(callId) { app.callList.value?.firstOrNull { it.call.id == callId }?.call }
    val call by remember(callId) { dao.observe(callId) }.collectAsStateWithLifecycle(initial)
    val noteVersion by ProcessingStatus.version.collectAsStateWithLifecycle()
    val lastRun = remember(noteVersion) { com.callnotes.util.Prefs(context).lastProcessing }
    val segments by remember(callId) { dao.observeSegments(callId) }.collectAsStateWithLifecycle(emptyList())
    val processing by ProcessingStatus.state.collectAsStateWithLifecycle()
    val requested by ProcessingStatus.requested.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var showTrace by remember { mutableStateOf(false) }
    var showTech by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    val c = call ?: return
    // Shown as "starting" at once (the request is visible before any database write), then
    // the service takes it ahead of the queue.
    val startTranscription: () -> Unit = {
        ProcessingStatus.request(c.id)
        scope.launch(Dispatchers.IO) {
            dao.setStatus(c.id, CallStatus.RECORDED, null, null)
            withContext(Dispatchers.Main) { onTranscribe() }
        }
    }
    val path = c.filePath
    val traceFile = path?.let { CallTrace.fileFor(File(it)) }?.takeIf { it.exists() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName(c), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") }
                },
                actions = {
                    if (path != null) IconButton(onClick = { shareRecording(context, path) }) {
                        Icon(Icons.Filled.Share, "שיתוף")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "מחיקה") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Avatar(c, size = 52)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(formatDateTime(c.startedAt), style = MaterialTheme.typography.bodyMedium)
                        Text("${kindLabel(c)} · ${formatDuration(c.endedAt - c.startedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        c.number?.takeIf { c.contactName != null }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (path != null) item {
                Spacer(Modifier.size(8.dp))
                PlayerPanel(c.id, path, player)
            }

            item { SectionHeader("תמלול") }

            item {
                TranscriptState(c, processing, queued = c.id in requested, lastRun = lastRun,
                    onStart = startTranscription, onShowLog = { showLog = true })
            }

            if (c.status == CallStatus.TRANSCRIBED && c.id !in requested) {
                if (segments.isEmpty()) item {
                    Text("לא זוהה דיבור בהקלטה.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                }
                items(segments, key = { it.id }) { seg ->
                    val active = player.loadedId == c.id && player.positionMs in seg.startMs..seg.endMs
                    SegmentRow(seg, active) { path?.let { player.playFrom(c.id, it, seg.startMs.toInt()) } }
                }
                item {
                    TextButton(onClick = startTranscription, modifier = Modifier.padding(top = 4.dp)) { Text("תמלל מחדש") }
                }
            }

            item {
                SectionHeader("פרטים טכניים")
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            c.transcriptModel?.let { "תומלל עם $it" } ?: "נתוני אבחון של ההקלטה",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showTech = !showTech }) { Text(if (showTech) "הסתרה" else "הצגה") }
                    }
                    if (showTech) {
                        TechChips(c)
                        if (traceFile != null) {
                            OutlinedButton(onClick = { showTrace = true }, modifier = Modifier.padding(top = 8.dp)) {
                                Text("יומן אבחון")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTrace && traceFile != null) TraceDialog(traceFile, onDismiss = { showTrace = false })
    if (showLog) TraceDialog(ProcessingLog.file(context), onDismiss = { showLog = false }, title = "יומן תמלול")

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("למחוק את השיחה?") },
        text = { Text("ההקלטה והתמלול יימחקו מהמכשיר לצמיתות.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                if (player.loadedId == c.id) player.release()
                scope.launch(Dispatchers.IO) {
                    path?.let { File(it).delete(); CallTrace.fileFor(File(it)).delete() }
                    dao.delete(c.id)
                }
                onBack()
            }) { Text("מחיקה", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } },
    )
}

@Composable
private fun PlayerPanel(id: Long, path: String, player: AudioPlayer) {
    val loaded = player.loadedId == id
    val playing = loaded && player.isPlaying
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = if (loaded) player.durationMs.toFloat() else 0f
    val position = dragging ?: if (loaded) player.positionMs.toFloat() else 0f

    Panel {
        // Media controls read left-to-right even in Hebrew.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = { player.toggle(id, path) },
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(if (playing) PauseIcon else Icons.Filled.PlayArrow, if (playing) "השהיה" else "השמעה")
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Slider(
                        value = position.coerceIn(0f, duration.coerceAtLeast(1f)),
                        onValueChange = { if (loaded) dragging = it },
                        onValueChangeFinished = { dragging?.let { player.seekTo(it.toInt()) }; dragging = null },
                        valueRange = 0f..duration.coerceAtLeast(1f),
                        enabled = loaded,
                    )
                    Row {
                        Text(formatDuration(position.toLong()), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(if (loaded) formatDuration(player.durationMs.toLong()) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptState(
    c: CallEntity,
    processing: ProcessingStatus.State,
    queued: Boolean,
    lastRun: String?,
    onStart: () -> Unit,
    onShowLog: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val onThis = processing.running && processing.callId == c.id
    val busyElsewhere = processing.running && processing.callId != null && processing.callId != c.id

    @Composable
    fun LastRun() {
        val text = lastRun ?: return
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("הרצה אחרונה: $text", style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = onShowLog) { Text("יומן") }
        }
    }

    @Composable
    fun Progress(title: String, detail: String?, fraction: Float?) {
      Panel {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.size(10.dp))
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
      }
    }

    when {
        // The model is loading for this call (before its status changes).
        onThis && processing.phase == "loading" -> Progress(
            "טוען את מודל התמלול… ${elapsed(processing.since)}",
            "בטעינה הראשונה זה יכול לקחת עד דקה או שתיים.",
            null,
        )
        c.status == CallStatus.TRANSCRIBING && onThis -> Progress(
            if (processing.chunksTotal > 0) "מתמלל… מקטע ${processing.chunksDone + 1} מתוך ${processing.chunksTotal}"
            else "מכין את האודיו…",
            if (processing.chunksTotal > 0) "כל מקטע הוא כחצי דקה של שיחה." else null,
            if (processing.chunksTotal > 0) processing.chunksDone.toFloat() / processing.chunksTotal else null,
        )
        c.status == CallStatus.TRANSCRIBING -> Panel {
            // Status says transcribing but nothing runs: the run was cut off.
            Text("התמלול נעצר לפני שהסתיים.", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onStart, modifier = Modifier.padding(top = 8.dp)) { Text("נסה שוב") }
            LastRun()
        }
        queued && busyElsewhere -> Progress(
            "מתחיל… עוצר קודם תמלול אוטומטי של שיחה אחרת",
            "השיחה הזו תתומלל ראשונה; השנייה תחזור לתור.",
            null,
        )
        queued && processing.phase == "waiting_thermal" -> Progress(
            "הטלפון חם — התמלול יתחיל כשיתקרר", null, null,
        )
        queued -> Progress("מתחיל תמלול…", null, null)
        c.status == CallStatus.TRANSCRIBED -> Unit
        c.status == CallStatus.RECORDED -> Panel {
            Text("ההקלטה ממתינה לתמלול.", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (busyElsewhere) "כרגע מתומללת שיחה אחרת. אפשר להקדים את זו:"
                else "התמלול רץ ברקע כשמותקן מודל. אפשר להתחיל אותו עכשיו:",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onStart, modifier = Modifier.padding(top = 8.dp)) { Text("תמלל עכשיו") }
            LastRun()
        }
        c.status == CallStatus.TRANSCRIBE_FAILED -> Panel {
            Text("התמלול נכשל", style = MaterialTheme.typography.titleSmall, color = cs.error)
            c.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onStart, modifier = Modifier.padding(top = 8.dp)) { Text("נסה שוב") }
            LastRun()
        }
        c.status == CallStatus.RECORD_FAILED -> Panel {
            Text("לא נשמרה הקלטה לשיחה הזו.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "0:42" since [since], ticking every second. */
@Composable
private fun elapsed(since: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) { now = System.currentTimeMillis(); delay(1_000) }
    }
    return formatDuration((now - since).coerceAtLeast(0))
}

@Composable
private fun SegmentRow(seg: SegmentEntity, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "segment",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 3.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(formatDuration(seg.startMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Text(seg.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TechChips(c: CallEntity) {
    val failed = c.status == CallStatus.RECORD_FAILED
    val quiet = !failed && (c.silenced || c.peakLevel < SILENT_PEAK)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 8.dp)) {
        if (quiet) Pill("התקבל שקט", Tone.Bad)
        if (c.silenced) Pill("המערכת השתיקה את ההקלטה", Tone.Bad)
        c.audioSource?.let { Pill(if (it == "mic") "מקור: MIC" else "מקור: זיהוי דיבור") }
        when (c.speakerResult) {
            SpeakerResult.ON -> Pill("רמקול: פעיל")
            SpeakerResult.FAILED -> Pill("רמקול: לא הופעל", Tone.Bad)
            SpeakerResult.SKIPPED_HEADSET -> Pill("אוזניות מחוברות")
        }
        if (!failed && !c.foreground) Pill("ללא שירות חזית", Tone.Bad)
        Pill("עוצמה מרבית ${c.peakLevel}")
    }
}
