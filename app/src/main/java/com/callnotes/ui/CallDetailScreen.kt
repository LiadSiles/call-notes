package com.callnotes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.callnotes.inference.ModelRole
import com.callnotes.inference.SourceFinder
import com.callnotes.processing.Phase
import com.callnotes.processing.ProcessingLog
import com.callnotes.processing.ProcessingStatus
import com.callnotes.processing.Stage
import com.callnotes.processing.TranscriptionService
import com.callnotes.util.Energy
import com.callnotes.util.Notifications
import com.callnotes.util.Prefs
import com.callnotes.util.RecordingFiles
import com.callnotes.util.TaskActions
import com.callnotes.util.TaskReminders
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Below this peak level (~-40 dBFS) the recording is effectively silent. */
private const val SILENT_PEAK = 330

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailScreen(
    callId: Long,
    player: AudioPlayer,
    onBack: () -> Unit,
    onTranscribe: () -> Unit,
    /** Transcript line (segment index) to show on open, e.g. from the tasks screen. */
    focusSegment: Int? = null,
    /** Text to locate when there's no line number (task quote / title). */
    focusText: String? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val dao = app.database.calls()
    val scope = rememberCoroutineScope()
    // Start from the row we already have, so the screen never opens blank.
    val initial = remember(callId) { app.callList.value?.firstOrNull { it.call.id == callId }?.call }
    val call by remember(callId) { dao.observe(callId) }.collectAsStateWithLifecycle(initial)
    val noteVersion by ProcessingStatus.version.collectAsStateWithLifecycle()
    val lastRun = remember(noteVersion) { Prefs(context).lastProcessing }
    val segments by remember(callId) { dao.observeSegments(callId) }.collectAsStateWithLifecycle(emptyList())
    val processing by ProcessingStatus.state.collectAsStateWithLifecycle()
    val summaryState by ProcessingStatus.summary.collectAsStateWithLifecycle()
    val queue by ProcessingStatus.queue.collectAsStateWithLifecycle()
    val tasks by remember(callId) { dao.observeTasks(callId) }.collectAsStateWithLifecycle(emptyList())
    val modelsSnap by app.models.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var showTrace by remember { mutableStateOf(false) }
    var showTech by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    /** Segment shown highlighted after a jump from a summary item or task. */
    var highlight by remember { mutableStateOf<Int?>(null) }
    /** Segment whose speaker the user is correcting (long press). */
    var fixing by remember { mutableStateOf<SegmentEntity?>(null) }
    var speakersEdited by remember(callId) { mutableStateOf(false) }
    /** Lines set aside as background speech are hidden until the user asks to see them. */
    var showBackground by remember(callId) { mutableStateOf(false) }
    val backgroundCount = segments.count { it.background }

    val c = call ?: return
    /** This call's place in the transcription / summary queue (null = not waiting). */
    val transcribePlace = ProcessingStatus.placeOf(queue, Stage.TRANSCRIBE, c.id)
    val summaryPlace = ProcessingStatus.placeOf(queue, Stage.SUMMARY, c.id)
    val transcribed = c.status == CallStatus.TRANSCRIBED && transcribePlace == null
    /** Over the length limit: transcribed and summarised only when the user asks. */
    val limitMinutes = remember(noteVersion) { Prefs(context).autoMaxMinutes }
    val manualOnly = remember(noteVersion, c.id) { Prefs(context).tooLongForAuto(c.endedAt - c.startedAt) }
    val autoForThis = remember(noteVersion) { Prefs(context).autoSummarize } && !manualOnly
    // Items before the first transcript line: header, player, summary, "תמלול", transcript state.
    val segmentStart = 1 + (if (c.filePath != null || c.audioDeletedAt != null) 1 else 0) + (if (transcribed) 1 else 0) + 2 +
        (if (transcribed && backgroundCount > 0) 1 else 0)
    val showLine: (Int) -> Unit = { idx ->
        if (idx in segments.indices) {
            if (segments[idx].background) showBackground = true
            highlight = idx
            scope.launch {
                // One line of context above the target.
                listState.animateScrollToItem((segmentStart + idx - 1).coerceAtLeast(0))
                kotlinx.coroutines.delay(3_000)
                if (highlight == idx) highlight = null
            }
        }
    }
    LaunchedEffect(focusSegment, focusText, segments.size) {
        if ((focusSegment != null || focusText != null) && segments.isNotEmpty()) {
            val target = focusSegment?.takeIf { it in segments.indices }
                ?: focusText?.let { SourceFinder.bestLine(it, segments.map { s -> s.text }) }
            onFocusConsumed()
            target?.let(showLine)
        }
    }
    // Shown in the queue at once (before any database write); the service takes it in turn.
    val startTranscription: () -> Unit = {
        ProcessingStatus.requestTranscription(c.id)
        scope.launch(Dispatchers.IO) {
            dao.setStatusOnly(c.id, CallStatus.RECORDED)
            withContext(Dispatchers.Main) { onTranscribe() }
        }
    }
    val cancelTranscription: () -> Unit = { TranscriptionService.cancelCall(context, c.id) }
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
            state = listState,
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
                        callStage(c, processing, summaryState, manualOnly, queue).let { (text, tone) ->
                            Pill(text, tone, Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }

            if (path != null) item {
                Spacer(Modifier.size(8.dp))
                PlayerPanel(c.id, path, player)
            } else if (c.audioDeletedAt != null) item {
                Text(
                    "ההקלטה נמחקה ב-${formatDateTime(c.audioDeletedAt)} לפי הגדרת השמירה. התמלול והסיכום נשמרו.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (transcribed) item {
                SummarySection(
                    c, tasks, summaryState,
                    queuePlace = summaryPlace,
                    modelInstalled = modelsSnap.active(ModelRole.ANALYZE) != null,
                    autoOn = autoForThis,
                    onSummarise = {
                        scope.launch(Dispatchers.IO) {
                            dao.clearAnalysis(c.id)
                            TranscriptionService.start(context, analyseId = c.id)
                        }
                    },
                    onCancel = cancelTranscription,
                    onTaskDone = { t, done -> scope.launch(Dispatchers.IO) { TaskActions.setDone(context, t, done) } },
                    onShowLine = showLine,
                    speakersEdited = speakersEdited,
                    lineTexts = segments.map { it.text },
                )
            }

            item { SectionHeader("תמלול") }

            item {
                TranscriptState(c, processing, queuePlace = transcribePlace, lastRun = lastRun,
                    manualOnlyMinutes = if (manualOnly) limitMinutes else null,
                    onStart = startTranscription, onCancel = cancelTranscription, onShowLog = { showLog = true })
            }

            if (transcribed) {
                if (segments.isEmpty()) item {
                    Text("לא זוהה דיבור בהקלטה.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                }
                if (backgroundCount > 0) item {
                    BackgroundToggle(backgroundCount, showBackground) { showBackground = !showBackground }
                }
                val conversation = segments.any { it.speaker != null }
                // Every line keeps its own item (hidden ones are empty), so positions stay stable.
                itemsIndexed(segments, key = { _, it -> it.id }) { i, seg ->
                    val active = highlight == i || player.loadedId == c.id && player.positionMs in seg.startMs..seg.endMs
                    val seek = { path?.let { player.playFrom(c.id, it, seg.startMs.toInt()) }; Unit }
                    if (seg.background) {
                        if (showBackground) BackgroundLine(seg, active, onClick = seek, onLongClick = { fixing = seg })
                    } else if (conversation) {
                        // Name above the first line of each turn (background lines don't break a turn).
                        val previous = (i - 1 downTo 0).firstOrNull { !segments[it].background }
                        val newTurn = previous == null || segments[previous].speaker != seg.speaker
                        val isMe = seg.speaker != null && seg.speaker == c.meSpeaker
                        // Mine on the start side (right, in Hebrew); otherwise speaker 1 / the other side on the end.
                        val alignEnd = if (c.meSpeaker != null) !isMe else seg.speaker == 1
                        ChatLine(seg, speakerLabel(c, seg.speaker), isMe = isMe, alignEnd = alignEnd,
                            showName = newTurn, active = active, onClick = seek, onLongClick = { fixing = seg })
                    } else {
                        SegmentRow(seg, active, onClick = seek, onLongClick = { fixing = seg })
                    }
                }
                if (conversation) item {
                    Text(
                        "לחיצה ארוכה על הודעה מאפשרת לתקן מי אמר אותה, או להחליף בין הדוברים בכל השיחה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                item {
                    TextButton(onClick = startTranscription, modifier = Modifier.padding(top = 4.dp)) { Text("תמלל מחדש") }
                }
            }

            item {
                SectionHeader("פרטים טכניים")
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.transcriptModel?.let { "תומלל עם $it" } ?: "נתוני אבחון של ההקלטה",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // What processing this call cost (whole phone during the operation; not while charging).
                            val costs = listOfNotNull(
                                c.transcribeMs?.let { "תמלול: ${formatDuration(it)}" + (Energy.label(c.transcribeEnergy)?.let { e -> " · $e" } ?: " · בטעינה / לא נמדד") },
                                c.analyzeMs?.let { "סיכום: ${formatDuration(it)}" + (Energy.label(c.analyzeEnergy)?.let { e -> " · $e" } ?: " · בטעינה / לא נמדד") },
                            )
                            if (costs.isNotEmpty()) Text(
                                costs.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

    fixing?.let { seg ->
        SpeakerFixDialog(
            c, seg,
            onDismiss = { fixing = null },
            onSetLine = { speaker ->
                fixing = null
                speakersEdited = true
                scope.launch(Dispatchers.IO) { dao.setSegmentSpeaker(seg.id, speaker) }
            },
            onSetMe = { me ->
                fixing = null
                speakersEdited = true
                scope.launch(Dispatchers.IO) { dao.setMeSpeaker(c.id, me) }
            },
            onSetBackground = { bg ->
                fixing = null
                speakersEdited = true
                scope.launch(Dispatchers.IO) { dao.setSegmentBackground(seg.id, bg) }
            },
        )
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
                    path?.let(RecordingFiles::delete)
                    tasks.forEach { Notifications.cancelTask(context, it.id) }
                    dao.delete(c.id)
                    TaskReminders.plan(context)
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
    /** Place in the transcription queue; null = not waiting. */
    queuePlace: Int?,
    lastRun: String?,
    /** Set when the call is over the automatic length limit (the limit, in minutes). */
    manualOnlyMinutes: Int?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onShowLog: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val onThis = processing.running && processing.callId == c.id
    // Transcribing something else (another call or a voice note), or loading the model for it.
    val busyElsewhere = processing.running && processing.phase.isNotEmpty() && !onThis
    val queued = queuePlace != null

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
    fun Waiting(title: String, detail: String) {
      Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCancel) { Text("ביטול") }
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
    }

    @Composable
    fun Progress(title: String, detail: String?, fraction: Float?) {
      Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCancel) { Text("ביטול") }
        }
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.size(10.dp))
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
      }
    }

    when {
        onThis && processing.phase == Phase.PREPARING_MODEL -> Progress(
            "מכין גרסה מהירה של מודל התמלול… " +
                if (processing.progressMax > 0) "${processing.progress * 100 / processing.progressMax}%" else "",
            "קורה פעם אחת אחרי בחירת מהירות חדשה בהגדרות. העותק נשמר בטלפון בלבד.",
            if (processing.progressMax > 0) processing.progress.toFloat() / processing.progressMax else null,
        )
        // The model is loading for this call (before its status changes).
        onThis && c.status != CallStatus.TRANSCRIBING && processing.phase == Phase.LOADING -> Progress(
            "טוען את מודל התמלול… ${elapsed(processing.since)}",
            "בטעינה הראשונה זה יכול לקחת עד דקה או שתיים.",
            null,
        )
        c.status == CallStatus.TRANSCRIBING && onThis && processing.phase == Phase.SPEAKERS -> Progress(
            "מזהה מי אמר מה…" +
                if (processing.progressMax > 0) " ${processing.progress * 100 / processing.progressMax}%" else "",
            "הפרדת הדוברים לפי הקול, על הטלפון.",
            if (processing.progressMax > 0) processing.progress.toFloat() / processing.progressMax else null,
        )
        c.status == CallStatus.TRANSCRIBING && onThis -> Progress(
            if (processing.progressMax > 0) "מתמלל… ${processing.progress * 100 / processing.progressMax}%"
            else "מכין את האודיו…",
            "התמלול רץ על הטלפון ויכול לקחת כמה דקות לשיחה ארוכה.",
            if (processing.progressMax > 0) processing.progress.toFloat() / processing.progressMax else null,
        )
        c.status == CallStatus.TRANSCRIBING -> Panel {
            // Status says transcribing but nothing runs: the run was cut off.
            Text("התמלול נעצר לפני שהסתיים.", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onStart, modifier = Modifier.padding(top = 8.dp)) { Text("נסה שוב") }
            LastRun()
        }
        queued && processing.phase == Phase.WAITING_THERMAL -> Progress(
            "הטלפון חם — התמלול יתחיל כשיתקרר", null, null,
        )
        queued && (busyElsewhere || queuePlace > 1) -> Waiting(
            queueLabel("לתמלול", queuePlace),
            "יתחיל כשמה שלפניו בתור יסתיים. אפשר לבקש עוד שיחות — כל בקשה נכנסת לתור לפי הסדר.",
        )
        queued -> Progress("מתחיל תמלול…", null, null)
        c.status == CallStatus.TRANSCRIBED -> Unit
        c.status == CallStatus.RECORDED -> Panel {
            Text(
                if (manualOnlyMinutes != null) "השיחה ארוכה מ-$manualOnlyMinutes דקות, ולכן לא תתומלל אוטומטית."
                else "ההקלטה ממתינה לתמלול.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when {
                    manualOnlyMinutes != null -> "כך הוגדר בהגדרות ← תמלול. אפשר לתמלל ולסכם אותה עכשיו:"
                    busyElsewhere -> "כרגע מתומלל משהו אחר. אפשר להוסיף את השיחה הזו לתור:"
                    else -> "התמלול רץ ברקע כשמותקן מודל. אפשר להתחיל אותו עכשיו:"
                },
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onStart, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (busyElsewhere) "הוספה לתור" else "תמלל עכשיו")
            }
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
        c.status == CallStatus.HELD -> Panel {
            Text("התמלול בוטל.", style = MaterialTheme.typography.bodyMedium)
            Text("ההקלטה לא תתומלל אוטומטית. אפשר לתמלל אותה בכל זמן:",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            FilledTonalButton(onClick = onStart, modifier = Modifier.padding(top = 8.dp)) { Text("תמלל עכשיו") }
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
private fun SegmentRow(seg: SegmentEntity, active: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "segment",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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

/**
 * Corrects who said a line, or who is who in the whole call. Speaker indexes stay 0/1; the
 * owner is whichever index `meSpeaker` points at.
 */
@Composable
private fun SpeakerFixDialog(
    c: CallEntity,
    seg: SegmentEntity,
    onDismiss: () -> Unit,
    onSetLine: (Int) -> Unit,
    onSetMe: (Int?) -> Unit,
    onSetBackground: (Boolean) -> Unit,
) {
    val speaker = seg.speaker ?: 0
    val other = c.contactName ?: c.number ?: "הצד השני"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (seg.background) "דיבור ברקע" else "מי אמר את זה?") },
        text = {
            Column {
                Text("\"${seg.text.take(120)}\"", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(12.dp))
                val me = c.meSpeaker
                if (seg.background) {
                    TextButton(onClick = { onSetBackground(false) }) { Text("זה חלק מהשיחה — להחזיר") }
                } else if (seg.speaker == null) {
                    TextButton(onClick = { onSetBackground(true) }) { Text("דיבור ברקע — להסתיר ולא לסכם") }
                } else if (me != null) {
                    TextButton(onClick = { onSetLine(me) }, enabled = speaker != me) { Text("השורה הזו: אני") }
                    TextButton(onClick = { onSetLine(1 - me) }, enabled = speaker == me) { Text("השורה הזו: $other") }
                    TextButton(onClick = { onSetMe(1 - me) }) { Text("להחליף בין אני ל$other בכל השיחה") }
                } else {
                    // Who is who isn't known yet: this line decides it for the whole call.
                    TextButton(onClick = { onSetMe(speaker) }) { Text("זה אני (דובר ${speaker + 1} הוא אני)") }
                    TextButton(onClick = { onSetMe(1 - speaker) }) { Text("זה $other (דובר ${2 - speaker} הוא אני)") }
                    TextButton(onClick = { onSetLine(1 - speaker) }) { Text("השורה הזו שייכת לדובר ${2 - speaker}") }
                }
                if (!seg.background && seg.speaker != null) {
                    TextButton(onClick = { onSetBackground(true) }) { Text("דיבור ברקע — להסתיר ולא לסכם") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגירה") } },
    )
}

/** "N lines of background speech hidden · show" above the transcript. */
@Composable
internal fun BackgroundToggle(count: Int, shown: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (shown) "$count קטעים של דיבור ברקע מוצגים באפור; הם לא נכנסים לסיכום."
            else "$count קטעים של דיבור ברקע (אנשים שלא בשיחה) הוסתרו ולא נכנסים לסיכום.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        TextButton(onClick = onToggle) { Text(if (shown) "הסתרה" else "הצגה") }
    }
}

/** A line set aside as background speech, when shown: grey and small, long press to bring it back. */
@Composable
internal fun BackgroundLine(seg: SegmentEntity, active: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (active) cs.tertiaryContainer else Color.Transparent, label = "background")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("רקע · ${formatDuration(seg.startMs)}", style = MaterialTheme.typography.labelSmall, color = cs.outline,
            modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(seg.text, style = MaterialTheme.typography.bodyMedium, color = cs.outline,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.weight(1f))
    }
}

/** "אני", the other side's name, or "דובר 1/2" when it isn't known who is who. */
private fun speakerLabel(c: CallEntity, speaker: Int?): String = when {
    speaker == null -> ""
    c.meSpeaker == null -> "דובר ${speaker + 1}"
    speaker == c.meSpeaker -> "אני"
    else -> c.contactName ?: c.number ?: "הצד השני"
}

/** One line of the conversation as a chat bubble: mine on the start side, theirs on the end side. */
@Composable
private fun ChatLine(
    seg: SegmentEntity,
    name: String,
    isMe: Boolean,
    alignEnd: Boolean,
    showName: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val base = if (isMe || (!alignEnd)) cs.primaryContainer else cs.surfaceContainerHigh
    val bg by animateColorAsState(if (active) cs.tertiaryContainer else base, label = "bubble")
    Column(
        Modifier.fillMaxWidth().padding(top = if (showName) 10.dp else 3.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        if (showName) {
            Text("$name · ${formatDuration(seg.startMs)}", style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Box(
            Modifier
                .fillMaxWidth(0.86f)
                .wrapContentWidth(if (alignEnd) Alignment.End else Alignment.Start)
                .clip(MaterialTheme.shapes.medium)
                .background(bg)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(seg.text, style = MaterialTheme.typography.bodyLarge)
        }
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
