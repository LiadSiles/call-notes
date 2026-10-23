package com.callnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.callnotes.CallNotesApp
import com.callnotes.data.TaskEntity
import com.callnotes.data.TaskSource
import com.callnotes.util.TaskActions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Adds a task by typing ([task] null) or edits one. Title, an optional day (today,
 * tomorrow or any date) and an optional time of day — the reminder pops up then, or at
 * the morning hour when only a day is set. A call's task also has who it is for. Delete
 * asks first. Nothing is saved until "שמירה".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditorDialog(
    task: TaskEntity?,
    onDismiss: () -> Unit,
    initialTitle: String = "",
    /** A new task written from a voice note: what was said (kept with the task). */
    voiceText: String? = null,
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    // Looked up on save only (keeps the dialog renderable in screenshot tests).
    fun app() = context.applicationContext as CallNotesApp
    var title by rememberSaveable { mutableStateOf(task?.title ?: initialTitle) }
    var date by rememberSaveable { mutableStateOf(task?.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }) }
    var time by rememberSaveable { mutableStateOf(task?.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }) }
    var owner by rememberSaveable { mutableStateOf(task?.owner ?: "me") }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val fromCall = task != null && !task.standalone

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (task == null) "משימה חדשה" else "עריכת משימה", Modifier.weight(1f))
                if (task != null) IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, "מחיקה", tint = MaterialTheme.colorScheme.error)
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(300) },
                    label = { Text("מה צריך לעשות") },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Label("מתי")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice("ללא", date == null) { date = null; time = null }
                    Choice("היום", date == today) { date = today }
                    Choice("מחר", date == today.plusDays(1)) { date = today.plusDays(1) }
                    val other = date != null && date != today && date != today.plusDays(1)
                    Choice(if (other) date!!.format(DAY) else "תאריך…", other, icon = true) { pickDate = true }
                }
                Label("שעה")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Choice(time?.format(HOUR_MIN) ?: "בחירת שעה", time != null) { pickTime = true }
                    if (time != null) IconButton(onClick = { time = null }) { Icon(Icons.Filled.Close, "בלי שעה", Modifier.size(18.dp)) }
                }
                Text(
                    when {
                        date == null -> "בלי תאריך אין תזכורת."
                        time == null -> "תזכורת ב-${com.callnotes.util.TaskReminders.HOUR}:00 ביום הזה."
                        else -> "תזכורת קופצת ב-${time!!.format(HOUR_MIN)}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (fromCall) {
                    Label("באחריות")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Choice("שלי", owner == "me") { owner = "me" }
                        Choice("הצד השני", owner == "other") { owner = "other" }
                        Choice("לא ידוע", owner == "unknown") { owner = "unknown" }
                    }
                }
                if (task?.source == TaskSource.VOICE && !task.quote.isNullOrBlank()) {
                    Text(
                        "מה שהוקלט: \"${task.quote}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val clean = title.trim().replace(Regex("\\s+"), " ")
                    val due = date?.toString()
                    val at = time?.takeIf { date != null }?.format(HOUR_MIN)
                    val saved = task?.copy(title = clean, dueDate = due, dueTime = at, owner = owner,
                        // A date the user set is theirs: drop the call's words for it.
                        duePhrase = task.duePhrase.takeIf { due == task.dueDate })
                        ?: TaskEntity(callId = TaskEntity.NO_CALL, idx = 0, title = clean, owner = "me", dueDate = due,
                            duePhrase = null, quote = voiceText, dueTime = at,
                            source = if (voiceText != null) TaskSource.VOICE else TaskSource.MANUAL)
                    app().let { a -> a.launchBackground { TaskActions.save(a, saved) } }
                    onSaved()
                    onDismiss()
                },
            ) { Text("שמירה") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // No reminders in the past: from today on.
                override fun isSelectableDate(utcTimeMillis: Long) = !utcDate(utcTimeMillis).isBefore(today)
                override fun isSelectableYear(year: Int) = year >= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = utcDate(it) }
                    pickDate = false
                }) { Text("אישור") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("ביטול") } },
        ) { DatePicker(state, showModeToggle = false) }
    }

    if (pickTime) {
        val initial = time ?: LocalTime.of(com.callnotes.util.TaskReminders.HOUR, 0)
        val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val picked = LocalTime.of(state.hour, state.minute)
                    time = picked
                    // A time with no day: today if it's still ahead, else tomorrow.
                    if (date == null) date = if (picked.isAfter(LocalTime.now())) today else today.plusDays(1)
                    pickTime = false
                }) { Text("אישור") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("ביטול") } },
        )
    }

    if (confirmDelete && task != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("למחוק את המשימה?") },
            text = {
                Text(
                    if (fromCall) "המשימה תימחק. אם השיחה תסוכם שוב, היא עשויה לחזור."
                    else "המשימה והתזכורת שלה יימחקו."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app().let { a -> a.launchBackground { TaskActions.delete(a, task) } }
                    confirmDelete = false
                    onDismiss()
                }) { Text("מחיקה", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
}

@Composable
private fun Choice(text: String, selected: Boolean, icon: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = if (icon) {
            { Icon(Icons.Filled.DateRange, null, Modifier.size(16.dp)) }
        } else null,
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M")
private val HOUR_MIN: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
