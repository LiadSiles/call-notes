package com.callnotes.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shows a diagnostic log (timings and levels only, never what was said) and lets the user
 * share it: the whole log as a file, plus its newest lines as text — apps that receive text
 * cut it (at about 20,000 characters), which used to drop exactly the newest lines.
 */
@Composable
fun TraceDialog(file: File, onDismiss: () -> Unit, title: String = "יומן אבחון") {
    val context = LocalContext.current
    val text = remember(file) { runCatching { file.readText() }.getOrNull()?.ifBlank { null } ?: "היומן ריק." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, if (text.length <= SHARED_TEXT) text else "…\n" + text.takeLast(SHARED_TEXT).substringAfter('\n'))
                // A copy in the shared cache folder (emptied at app start), readable by the chosen app.
                runCatching {
                    val copy = File(File(context.cacheDir, "share").apply { mkdirs() }, "callnotes-log.txt")
                    file.copyTo(copy, overwrite = true)
                    send.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.files", copy))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "שיתוף יומן"))
            }) { Text("שיתוף") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("סגירה") } },
    )
}

/** Characters of the log sent as text (the newest); the whole log goes as a file. */
private const val SHARED_TEXT = 15_000
