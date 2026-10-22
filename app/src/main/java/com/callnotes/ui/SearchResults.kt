package com.callnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.callnotes.data.CallDao
import com.callnotes.data.CallKind
import com.callnotes.data.SearchHit

/** Searches transcripts, summaries, tasks and names. Everything stays on the phone. */
object Search {
    const val MIN_CHARS = 2

    suspend fun run(dao: CallDao, query: String): List<SearchHit> {
        val q = query.trim()
        if (q.length < MIN_CHARS) return emptyList()
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val lines = dao.searchLines(escaped)
        val calls = dao.searchCalls(escaped).filter { c -> lines.none { it.callId == c.callId } || c.text.contains(q, true) }
        return (calls + lines).sortedWith(compareByDescending<SearchHit> { it.startedAt }.thenBy { it.segmentIdx ?: -1 })
    }
}

/** Results instead of the call list while searching. */
fun LazyListScope.searchResults(
    query: String,
    hits: List<SearchHit>?,
    onOpen: (callId: Long, segment: Int?, text: String?) -> Unit,
) {
    if (query.trim().length < Search.MIN_CHARS) {
        item {
            Text("חיפוש בתמלולים, בסיכומים, במשימות ובשמות.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        return
    }
    if (hits == null) return
    if (hits.isEmpty()) {
        item { EmptyState(icon = Icons.Filled.Search, title = "לא נמצא", body = "אין תוצאות ל\"${query.trim()}\".") }
        return
    }
    item {
        Text(if (hits.size == 1) "תוצאה אחת" else "${hits.size} תוצאות", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    }
    items(hits, key = { "${it.callId}_${it.segmentIdx}" }) { h ->
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().clickable { onOpen(h.callId, h.segmentIdx, if (h.segmentIdx == null) null else query.trim()) },
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("${who(h)} · ${formatDateTime(h.startedAt)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    highlight(snippet(h.text, query.trim()), query.trim(), MaterialTheme.colorScheme.primary),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (h.segmentIdx == null && !h.text.contains(query.trim(), true)) {
                    Text("נמצא בסיכום", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

private fun who(h: SearchHit) = h.contactName ?: h.number ?: when (h.kind) {
    CallKind.VOIP -> "שיחת אינטרנט"
    CallKind.IMPORT -> "קובץ מיובא"
    else -> if (h.incoming) "שיחה נכנסת" else "שיחה יוצאת"
}

/** A window of the text around the first match, so long lines show the relevant part. */
internal fun snippet(text: String, q: String, around: Int = 60): String {
    val i = text.indexOf(q, ignoreCase = true)
    if (i < 0 || text.length <= around * 2) return text
    val from = (i - around).coerceAtLeast(0)
    val to = (i + q.length + around).coerceAtMost(text.length)
    return (if (from > 0) "…" else "") + text.substring(from, to) + (if (to < text.length) "…" else "")
}

private fun highlight(text: String, q: String, color: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        var i = text.indexOf(q, ignoreCase = true)
        while (i >= 0 && q.isNotEmpty()) {
            addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), i, i + q.length)
            i = text.indexOf(q, i + q.length, ignoreCase = true)
        }
    }
