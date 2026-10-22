package com.callnotes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Checkbox
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
import com.callnotes.data.TaskListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Every task from every call: open ones first (by due date), then the done ones.
 * Tick to mark done; tap to open the call at the line the task came from.
 */
@Composable
fun TasksScreen(
    contentPadding: PaddingValues,
    focusTaskId: Long?,
    onFocusConsumed: () -> Unit,
    onOpenCall: (callId: Long, segment: Int?, text: String?) -> Unit,
) {
    val context = LocalContext.current
    val dao = (context.applicationContext as CallNotesApp).database.calls()
    val all by remember { dao.observeAllTasks() }.collectAsStateWithLifecycle(null)
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
        scope.launch(Dispatchers.IO) { com.callnotes.util.TaskActions.setDone(context, t.task, v) }
    }
    val unlink: (TaskListItem) -> Unit = { t ->
        scope.launch(Dispatchers.IO) { com.callnotes.util.TaskActions.unlinkDuplicate(context, t.task) }
    }
    val openItem: (TaskListItem) -> Unit = { t -> onOpenCall(t.task.callId, t.task.firstSegment, t.task.quote ?: t.task.title) }

    // From a "new task" notification: scroll to it and highlight it for a moment.
    LaunchedEffect(focusTaskId, all) {
        val asked = focusTaskId ?: return@LaunchedEffect
        if (all == null) return@LaunchedEffect
        // A repeat is shown inside the card of the task it repeats.
        val id = everything.firstOrNull { it.task.id == asked }?.task?.duplicateOf?.takeIf { it in ids } ?: asked
        val inOpen = open.indexOfFirst { it.task.id == id }
        val index = if (inOpen >= 0) 1 + inOpen else {
            showDone = true
            delay(80) // let the done section appear
            done.indexOfFirst { it.task.id == id }.takeIf { it >= 0 }?.let { 2 + open.size + it } ?: return@LaunchedEffect
        }
        onFocusConsumed()
        highlight = id
        listState.animateScrollToItem(index)
        delay(2_500)
        highlight = null
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 40.dp),
    ) {
        item {
            PageHeader("משימות", subtitle = if (open.isEmpty()) null else "${open.size} פתוחות · מהשיחות שלך") {}
        }
        if (all != null && tasks.isEmpty()) item {
            EmptyState(
                icon = Icons.Filled.CheckCircle,
                title = "אין עדיין משימות",
                body = "משימות שעלו בשיחות יופיעו כאן אחרי הסיכום, עם קישור לרגע בשיחה שבו נאמרו.",
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
                val owner = when (t.owner) {
                    "me" -> "באחריותי"
                    "other" -> "באחריות ${item.callContactName ?: "הצד השני"}"
                    else -> null
                }
                val meta = listOfNotNull(owner, dueLabel(t.dueDate, t.duePhrase), "$who · ${formatDateTime(item.callStartedAt)}")
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (!t.certain) Pill("לא בטוח", Tone.Bad, Modifier.padding(top = 6.dp))
                // Where else it came up: tap opens that call at the line.
                repeats.forEach { r ->
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "עלתה שוב: ${who(r)} · ${formatDateTime(r.callStartedAt)} ›",
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
                TaskExtras(t, "$who · ${formatDateTime(item.callStartedAt)}")
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

private fun who(item: TaskListItem) = item.callContactName ?: item.callNumber ?: when (item.callKind) {
    CallKind.VOIP -> "שיחת אינטרנט"
    CallKind.IMPORT -> "קובץ מיובא"
    else -> if (item.callIncoming) "שיחה נכנסת" else "שיחה יוצאת"
}

/** "עד 22.10 (מחר)", overdue marked. */
internal fun dueLabel(date: String?, phrase: String?): String? {
    val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val text = d?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    val late = d != null && d.isBefore(LocalDate.now())
    return when {
        text != null && phrase != null -> "עד $text ($phrase)" + if (late) " · עבר המועד" else ""
        text != null -> "עד $text" + if (late) " · עבר המועד" else ""
        phrase != null -> phrase
        else -> null
    }
}
