package com.callnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.CallNotesApp
import com.callnotes.data.CallKind
import com.callnotes.data.Caller
import com.callnotes.data.TaskEntity
import com.callnotes.data.TaskListItem
import com.callnotes.data.TaskSource
import com.callnotes.data.VoiceMemoEntity
import com.callnotes.inference.ModelRole
import com.callnotes.processing.ProcessingStatus
import com.callnotes.util.TaskActions
import com.callnotes.util.VoiceMemos
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Every task — from calls, typed in, or recorded: open ones first (by due date and time),
 * then the done ones. Tick to mark done; tap a call's task to open the call at the line it
 * came from, or one the user added to edit it. Voice notes still on their way to becoming
 * tasks show at the top. The buttons at the bottom add a task by voice or by typing.
 */
@Composable
fun TasksScreen(
    contentPadding: PaddingValues,
    focusTaskId: Long?,
    onFocusConsumed: () -> Unit,
    onOpenCall: (callId: Long, segment: Int?, text: String?) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as CallNotesApp
    val dao = app.database.calls()
    val all by remember { dao.observeAllTasks() }.collectAsStateWithLifecycle(null)
    val memos by remember { dao.observeMemos() }.collectAsStateWithLifecycle(emptyList())
    val transcribing by ProcessingStatus.state.collectAsStateWithLifecycle()
    val summarising by ProcessingStatus.summary.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    /** Typing a new task: its starting text, or null = closed. */
    var adding by remember { mutableStateOf<String?>(null) }
    /** The voice note the new task is being written from (removed once it is saved). */
    var fromMemo by remember { mutableStateOf<VoiceMemoEntity?>(null) }
    var recordingNote by remember { mutableStateOf(false) }
    var needModels by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recordingNote = true
    }
    val record: () -> Unit = {
        when {
            app.models.readyModel() == null || app.models.readyModel(ModelRole.ANALYZE) == null -> needModels = true
            !micAllowed(context) -> micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            else -> recordingNote = true
        }
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showDone by rememberSaveable { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<Long?>(null) }

    // A task said again in a later call is shown once, under the first call it came up in.
    val everything = all.orEmpty()
    val ids = remember(everything) { everything.map { it.task.id }.toSet() }
    val repeats = remember(everything) {
        everything.filter { it.task.duplicateOf != null && it.task.duplicateOf in ids }.groupBy { it.task.duplicateOf!! }
    }
    val tasks = everything.filter { it.task.duplicateOf == null || it.task.duplicateOf !in ids }
    val open = tasks.filter { !it.task.done }
    val done = tasks.filter { it.task.done }
    val tick: (TaskListItem, Boolean) -> Unit = { t, v ->
        scope.launch(Dispatchers.IO) { TaskActions.setDone(context, t.task, v) }
    }
    val unlink: (TaskListItem) -> Unit = { t ->
        scope.launch(Dispatchers.IO) { TaskActions.unlinkDuplicate(context, t.task) }
    }
    val openItem: (TaskListItem) -> Unit = { t ->
        if (t.task.standalone) editing = t.task
        else onOpenCall(t.task.callId, t.task.firstSegment, t.task.quote ?: t.task.title)
    }

    // From a "new task" notification: scroll to it and highlight it for a moment.
    LaunchedEffect(focusTaskId, all) {
        val asked = focusTaskId ?: return@LaunchedEffect
        if (all == null) return@LaunchedEffect
        // A repeat is shown inside the card of the task it repeats.
        val id = everything.firstOrNull { it.task.id == asked }?.task?.duplicateOf?.takeIf { it in ids } ?: asked
        val inOpen = open.indexOfFirst { it.task.id == id }
        val first = 1 + memos.size // header, then the voice notes
        val index = if (inOpen >= 0) first + inOpen else {
            showDone = true
            delay(80) // let the done section appear
            done.indexOfFirst { it.task.id == id }.takeIf { it >= 0 }?.let { first + 1 + open.size + it } ?: return@LaunchedEffect
        }
        onFocusConsumed()
        highlight = id
        listState.animateScrollToItem(index)
        delay(2_500)
        highlight = null
    }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Room at the bottom for the add buttons.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 104.dp),
        ) {
            item {
                PageHeader("משימות", subtitle = if (open.isEmpty()) null else "${open.size} פתוחות") {}
            }
            items(memos, key = { "memo-${it.id}" }) { m ->
                MemoCard(
                    m, transcribing, summarising,
                    onRetry = { app.launchBackground { VoiceMemos.retry(app, m) } },
                    onWrite = { fromMemo = m; adding = m.text.orEmpty() },
                    onDelete = { app.launchBackground { VoiceMemos.delete(app, m) } },
                )
            }
            if (all != null && tasks.isEmpty() && memos.isEmpty()) item {
                EmptyState(
                    icon = Icons.Filled.CheckCircle,
                    title = "אין עדיין משימות",
                    body = "משימות שעלו בשיחות יופיעו כאן אחרי הסיכום, עם קישור לרגע בשיחה שבו נאמרו. " +
                        "אפשר גם להוסיף משימה בכתיבה או בהקלטה קולית.",
                )
            }
            items(open, key = { it.task.id }) { t ->
                TaskCard(t, repeats[t.task.id].orEmpty(), highlight == t.task.id, onDone = { tick(t, it) }, onOpen = openItem, onUnlink = unlink)
            }
            if (done.isNotEmpty()) item {
                TextButton(onClick = { showDone = !showDone }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (showDone) "הסתרת משימות שבוצעו (${done.size})" else "משימות שבוצעו (${done.size})")
                }
            }
            if (showDone) items(done, key = { it.task.id }) { t ->
                TaskCard(t, repeats[t.task.id].orEmpty(), highlight == t.task.id, onDone = { tick(t, it) }, onOpen = openItem, onUnlink = unlink)
            }
        }
        AddTaskButtons(onRecord = record, onType = { fromMemo = null; adding = "" }, modifier = Modifier.align(Alignment.BottomStart))
    }

    editing?.let { t -> TaskEditorDialog(t, onDismiss = { editing = null }) }
    adding?.let { text ->
        val memo = fromMemo
        TaskEditorDialog(
            null,
            onDismiss = { adding = null; fromMemo = null },
            initialTitle = text,
            voiceText = memo?.text,
            onSaved = { if (memo != null) app.launchBackground { VoiceMemos.delete(app, memo) } },
        )
    }
    if (recordingNote) VoiceTaskDialog(onDismiss = { recordingNote = false })
    if (needModels) AlertDialog(
        onDismissRequest = { needModels = false },
        title = { Text("צריך מודלים") },
        text = { Text("כדי להפוך הקלטה למשימה צריך מודל תמלול ומודל סיכום. אפשר להוריד אותם בהגדרות. בינתיים אפשר להוסיף משימה בכתיבה.") },
        confirmButton = { TextButton(onClick = { needModels = false }) { Text("הבנתי") } },
    )
}

/**
 * The two ways to add a task, at the bottom corner: say it (microphone) or type it. In
 * the right-to-left layout BottomStart is the bottom-right corner, under the thumb.
 */
@Composable
internal fun AddTaskButtons(onRecord: () -> Unit, onType: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ExtendedFloatingActionButton(
            onClick = onType,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("משימה") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.width(12.dp))
        FloatingActionButton(
            onClick = onRecord,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) { Icon(AppIcons.Mic, "הקלטת משימה") }
    }
}

@Composable
internal fun TaskCard(
    item: TaskListItem,
    repeats: List<TaskListItem>,
    highlighted: Boolean,
    onDone: (Boolean) -> Unit,
    onOpen: (TaskListItem) -> Unit,
    onUnlink: (TaskListItem) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val t = item.task
    val bg by animateColorAsState(if (highlighted) cs.tertiaryContainer else cs.surfaceContainer, label = "task")
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = bg,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(MaterialTheme.shapes.medium).clickable { onOpen(item) },
    ) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = t.done, onCheckedChange = onDone)
            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                Text(
                    t.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    color = if (t.done) cs.onSurfaceVariant else cs.onSurface,
                )
                val who = who(item)
                val owner = when {
                    t.standalone -> null
                    t.owner == "me" -> "באחריותי"
                    t.owner == "other" -> "באחריות ${item.callContactName ?: "הצד השני"}"
                    else -> null
                }
                val meta = listOfNotNull(owner, dueLabel(t.dueDate, t.dueTime, t.duePhrase), "$who · ${formatShort(item.callStartedAt ?: t.createdAt)}")
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (!t.certain) Pill("לא בטוח", Tone.Bad, Modifier.padding(top = 6.dp))
                // Where else it came up: tap opens that call at the line.
                repeats.forEach { r ->
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "עלתה שוב: ${who(r)} · ${formatShort(r.callStartedAt ?: r.task.createdAt)} ›",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.primary,
                            modifier = Modifier.weight(1f).clickable { onOpen(r) },
                        )
                        Text(
                            "לא אותה משימה",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp).clickable { onUnlink(r) },
                        )
                    }
                }
                TaskExtras(t, if (t.standalone) null else "$who · ${formatDateTime(item.callStartedAt ?: t.createdAt)}")
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** Where the task came from: the call's name, or how the user added it. */
private fun who(item: TaskListItem) = when (item.task.source) {
    TaskSource.MANUAL -> "נוספה ידנית"
    TaskSource.VOICE -> "מהקלטה"
    else -> Caller.label(item.callContactName, item.callNumber, item.callKind ?: CallKind.CELLULAR,
        item.callIncoming ?: false)
}

/** "עד 22.10 17:00 (מחר)", overdue marked. */
internal fun dueLabel(date: String?, time: String?, phrase: String?): String? {
    val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val text = d?.format(DateTimeFormatter.ofPattern("d.M"))?.let { if (time != null) "$it $time" else it }
    val late = d != null && (d.isBefore(LocalDate.now()) || d == LocalDate.now() && time != null && time < java.time.LocalTime.now().toString().take(5))
    return when {
        text != null && phrase != null -> "עד $text ($phrase)" + if (late) " · עבר המועד" else ""
        text != null -> "עד $text" + if (late) " · עבר המועד" else ""
        phrase != null -> phrase
        else -> null
    }
}
