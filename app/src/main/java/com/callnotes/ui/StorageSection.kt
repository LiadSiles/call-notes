package com.callnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callnotes.processing.ProcessingStatus
import com.callnotes.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the app takes on the phone, by kind, and the largest files; "פינוי מקום" removes
 * only what is unused or made again on demand (after a confirm that lists it). Not while
 * processing runs (a file in use might be among them).
 */
@Composable
fun StorageSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<Storage.Report?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var showBiggest by remember { mutableStateOf(false) }
    var freed by remember { mutableStateOf<Long?>(null) }
    val processing by ProcessingStatus.state.collectAsStateWithLifecycle()
    val summary by ProcessingStatus.summary.collectAsStateWithLifecycle()
    val busy = processing.running || summary.running
    LaunchedEffect(tick) { report = withContext(Dispatchers.IO) { Storage.measure(context) } }

    val r = report
    CardGroup {
        if (r == null) {
            InfoRow("מחשב…")
            return@CardGroup
        }
        InfoRow("סך הכל", Storage.label(r.total))
        r.parts.forEach { p ->
            RowDivider()
            InfoRow(p.label, Storage.label(p.bytes))
        }
        RowDivider(inset = false)
        InfoRow("הקבצים הגדולים ביותר", if (showBiggest) null else "הצגה", onClick = { showBiggest = !showBiggest })
        if (showBiggest) Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp)) {
            r.biggest.forEach { (path, bytes) ->
                Text("${Storage.label(bytes)} · $path", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val canFree = r.freeable.sumOf { it.bytes }
        if (canFree > 0) {
            RowDivider(inset = false)
            InfoRow(
                "פינוי מקום · ${Storage.label(canFree)}",
                if (busy) "אפשר אחרי שהעיבוד ברקע יסתיים." else r.freeable.joinToString(" · ") { it.label },
            ) {
                TextButton(onClick = { confirm = true }, enabled = !busy) { Text("פינוי") }
            }
        }
    }
    Note(
        (freed?.let { "פונו ${Storage.label(it)}. " } ?: "") +
            "ההקלטות, התמלולים, הסיכומים והמודלים בשימוש לא נמחקים כאן. בהגדרות של אנדרואיד המספר כולל גם את האפליקציה עצמה."
    )

    if (confirm && r != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("לפנות ${Storage.label(r.freeable.sumOf { it.bytes })}?") },
            text = {
                Column {
                    r.freeable.forEach { Text("• ${it.label} — ${Storage.label(it.bytes)}", style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        freed = withContext(Dispatchers.IO) { Storage.free(context, r.freeable) }
                        tick++
                    }
                }) { Text("פינוי") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("ביטול") } },
        )
    }
}
