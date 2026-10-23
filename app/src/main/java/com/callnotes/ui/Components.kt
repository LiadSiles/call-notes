package com.callnotes.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.callnotes.data.CallEntity
import com.callnotes.data.CallKind
import com.callnotes.data.CallStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Section title above a group of cards. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) = GroupLabel(text, modifier)

/** White rounded panel used for every group of content. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) { content() }
    }
}

/** Colour of a [Pill]: Good (done), Info (part done), Busy (working now), Bad (failed), Neutral. */
enum class Tone { Neutral, Good, Info, Busy, Bad }

/** Small rounded label: status of a call, a model, the app. */
@Composable
fun Pill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.Neutral -> cs.surfaceVariant to cs.onSurfaceVariant
        Tone.Good -> cs.primaryContainer to cs.onPrimaryContainer
        Tone.Info -> cs.tertiaryContainer to cs.onTertiaryContainer
        Tone.Busy -> cs.secondaryContainer to cs.onSecondaryContainer
        Tone.Bad -> cs.errorContainer to cs.onErrorContainer
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg.copy(alpha = 0.75f)))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/** Round avatar with the contact's initial, or an icon-like glyph for unknown callers. */
@Composable
fun Avatar(call: CallEntity, size: Int = 44) {
    val cs = MaterialTheme.colorScheme
    val name = call.contactName?.trim().orEmpty()
    val glyph = when {
        call.kind == CallKind.IMPORT -> "♪"
        name.isNotEmpty() && name.first().isLetter() -> name.first().uppercase()
        call.kind == CallKind.VOIP -> "◎"
        else -> "#"
    }
    val t = com.callnotes.ui.theme.LocalTints.current
    val palette = listOf(t.green, t.indigo, t.brass, t.teal, t.plum, t.rose)
    val (bg, fg) = if (name.isEmpty()) t.slate else palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

/** Icon + text row used in empty states. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.size(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ---- Wording -------------------------------------------------------------------------

fun displayName(call: CallEntity): String =
    call.contactName ?: call.number ?: when (call.kind) {
        CallKind.VOIP -> "שיחת אינטרנט"
        CallKind.IMPORT -> "קובץ מיובא"
        else -> "מספר לא ידוע"
    }

fun kindLabel(call: CallEntity): String = when {
    call.kind == CallKind.IMPORT -> "קובץ מיובא"
    call.kind == CallKind.VOIP -> "שיחת אינטרנט"
    call.incoming -> "נכנסת"
    else -> "יוצאת"
}

/**
 * Where a call stands, in one label: nothing done yet / transcribed / summarised, or what
 * is happening to it now, or what failed. Shown on every call in the list and on the
 * call's own screen.
 *
 * [transcription] and [summary] are the live worker states; [manualOnly] means the call is
 * over the automatic-processing length limit.
 */
fun callStage(
    call: CallEntity,
    transcription: com.callnotes.processing.ProcessingStatus.State,
    summary: com.callnotes.processing.ProcessingStatus.State,
    manualOnly: Boolean = false,
): Pair<String, Tone> {
    val transcribingNow = transcription.running && transcription.callId == call.id
    val summarisingNow = summary.running && summary.callId == call.id
    return when (call.status) {
        CallStatus.RECORD_FAILED -> "ההקלטה נכשלה" to Tone.Bad
        CallStatus.TRANSCRIBING ->
            (if (transcribingNow && transcription.progressMax > 0) "מתמלל ${transcription.progress * 100 / transcription.progressMax}%"
            else "מתמלל…") to Tone.Busy
        CallStatus.TRANSCRIBE_FAILED -> "התמלול נכשל" to Tone.Bad
        CallStatus.RECORDED ->
            (if (manualOnly) "טרם תומלל · ארוכה, לתמלול ידני" else "טרם תומלל") to Tone.Neutral
        CallStatus.HELD -> "טרם תומלל · התמלול בוטל" to Tone.Neutral
        CallStatus.TRANSCRIBED -> when {
            summarisingNow -> "תומלל · מסכם…" to Tone.Busy
            call.analysisStatus == com.callnotes.data.AnalysisStatus.DONE -> "תומלל וסוכם" to Tone.Good
            call.analysisStatus == com.callnotes.data.AnalysisStatus.FAILED -> "תומלל · הסיכום נכשל" to Tone.Bad
            else -> "תומלל · טרם סוכם" to Tone.Info
        }
        else -> "טרם עובד" to Tone.Neutral
    }
}

fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}

/** "22.10, 14:47" — compact date for secondary lines. */
fun formatShort(millis: Long): String = SimpleDateFormat("d.M, HH:mm", Locale.US).format(Date(millis))

fun formatTime(millis: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

fun formatDateTime(millis: Long): String =
    SimpleDateFormat("EEEE, d בMMMM · HH:mm", Locale.forLanguageTag("he")).format(Date(millis))

/** "היום", "אתמול", or a date — for list section headers. */
fun dayLabel(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(cal, today) -> "היום"
        sameDay(cal, yesterday) -> "אתמול"
        else -> SimpleDateFormat("EEEE, d בMMMM", Locale.forLanguageTag("he")).format(Date(millis))
    }
}

fun gb(bytes: Long) = "%.1fGB".format(bytes / 1_000_000_000.0)

fun shareRecording(context: Context, path: String) {
    // An encrypted recording is shared as a decrypted temporary copy (removed at next start).
    val file = if (com.callnotes.security.SealedFile.isSealed(path)) {
        runCatching { com.callnotes.security.DataProtection.plainCopy(context, File(path), "share") }.getOrNull() ?: return
    } else File(path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("audio/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "שיתוף הקלטה").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

