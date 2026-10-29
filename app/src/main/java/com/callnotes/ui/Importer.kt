package com.callnotes.ui

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.callnotes.CallNotesApp
import com.callnotes.data.CallEntity
import com.callnotes.data.CallKind
import com.callnotes.data.CallStatus
import com.callnotes.data.SpeakerResult
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Brings an audio file into the app — picked in the app ("ייבוא אודיו") or shared to it
 * from WhatsApp or any other app — as an entry in the calls list. Any format Android can
 * decode: WhatsApp voice messages (Ogg/Opus), M4A/AAC, MP3, AMR, WAV, 3GP, FLAC… The file
 * is copied into the app's private storage; the original stays where it was.
 */
object Importer {

    /** Copies [uri] and adds it to the list; returns its id. Throws if it holds no audio. */
    suspend fun import(context: Context, uri: Uri): Long {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val ext = extension(name, resolver.getType(uri))
        val now = System.currentTimeMillis()
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "import_$now.$ext")
        try {
            val input = resolver.openInputStream(uri) ?: error("cannot open $uri")
            input.use { i -> file.outputStream().use { i.copyTo(it) } }
            require(hasAudio(file)) { "no audio track" }
        } catch (e: Exception) {
            file.delete() // no half-copied or useless file left behind
            throw e
        }

        val (durationMs, recordedAt) = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(file.absolutePath)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                ms to r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            }
        }.getOrDefault(0L to null)
        val startedAt = recordingDay(name, recordedAt, now)

        val entity = CallEntity(
            startedAt = startedAt,
            endedAt = startedAt + durationMs,
            incoming = false,
            number = null,
            contactName = label(name),
            filePath = file.absolutePath,
            audioSource = null,
            peakLevel = 32767,
            silenced = false,
            speakerResult = SpeakerResult.NOT_APPLICABLE,
            speakerMethod = null,
            status = CallStatus.RECORDED,
            kind = CallKind.IMPORT,
            foreground = true,
        )
        return (context.applicationContext as CallNotesApp).database.calls().insert(entity)
    }

    /** WhatsApp's names: "PTT-20261029-WA0003.opus" (voice message), "AUD-20261029-WA0001.m4a". */
    private val WHATSAPP = Regex("""^(PTT|AUD)-(\d{8})-WA\d+""", RegexOption.IGNORE_CASE)
    private val DATE_IN_NAME = Regex("""(20\d{2})-?(\d{2})-?(\d{2})""")

    /** How the file is named in the list. */
    internal fun label(name: String?): String {
        val base = name?.substringBeforeLast('.')?.trim().orEmpty()
        val wa = WHATSAPP.find(base)
        return when {
            wa?.groupValues?.get(1).equals("PTT", ignoreCase = true) -> "הודעה קולית מוואטסאפ"
            wa != null -> "הקלטה מוואטסאפ"
            base.startsWith("WhatsApp", ignoreCase = true) -> "הקלטה מוואטסאפ"
            base.isEmpty() -> "קובץ אודיו"
            else -> base
        }
    }

    /**
     * When it was recorded, as far as can be told — dates in the summary ("מחר") count from
     * it: the date in the file's name, else the file's own date, at noon; today = now.
     */
    internal fun recordingDay(name: String?, metadataDate: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = listOfNotNull(name, metadataDate).firstNotNullOfOrNull { text ->
            DATE_IN_NAME.find(text)?.destructured?.let { (y, m, d) ->
                runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
            }
        }
        return if (day == null || !day.isBefore(today)) now
        else day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    }

    /** An extension for the copy: the original's, else from the type ("audio/ogg" → ogg). */
    internal fun extension(name: String?, mime: String?): String {
        val ext = name?.takeIf { '.' in it }?.substringAfterLast('.')
            ?: mime?.substringBefore(';')?.trim()?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        // Only letters and digits (it names a file we create).
        return (ext ?: "audio").filter { it.isLetterOrDigit() }.take(5).ifEmpty { "audio" }
    }

    private fun hasAudio(file: File): Boolean {
        val x = MediaExtractor()
        return try {
            x.setDataSource(file.absolutePath)
            (0 until x.trackCount).any { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (_: Exception) {
            false
        } finally {
            x.release()
        }
    }
}
