package com.callnotes.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.callnotes.data.TaskEntity
import com.callnotes.util.TaskActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Small actions under a task: add it to Google Calendar, and for a task marked as a repeat
 * of another call's task, undo that ("not the same task").
 */
@Composable
internal fun TaskExtras(t: TaskEntity, callLabel: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pad = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { TaskActions.addToCalendar(context, t, callLabel) }, contentPadding = pad,
            modifier = Modifier.height(36.dp)) {
            Text(if (t.dueDate != null) "הוספה ליומן" else "הוספה ליומן (לבחור תאריך)", style = MaterialTheme.typography.labelLarge)
        }
        if (t.duplicateOf != null) {
            TextButton(onClick = { scope.launch(Dispatchers.IO) { TaskActions.unlinkDuplicate(context, t) } },
                contentPadding = pad, modifier = Modifier.height(36.dp)) {
                Text("לא אותה משימה", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
