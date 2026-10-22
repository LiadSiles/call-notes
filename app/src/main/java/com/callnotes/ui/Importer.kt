package com.callnotes.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.callnotes.CallNotesApp
import com.callnotes.data.CallEntity
import com.callnotes.data.CallKind
import com.callnotes.data.CallStatus
import com.callnotes.data.SpeakerResult
import java.io.File

/** Copies a user-picked audio file into the app and adds it to the list as an import. */
object Importer {

    suspend fun import(context: Context, uri: Uri): Long {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "קובץ אודיו"
        // Only letters and digits from the original extension (it names a file we create).
        val ext = name.substringAfterLast('.', "audio").filter { it.isLetterOrDigit() }.take(5).ifEmpty { "audio" }
        val now = System.currentTimeMillis()
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "import_$now.$ext")
        try {
            val input = resolver.openInputStream(uri) ?: error("cannot open $uri")
            input.use { i -> file.outputStream().use { i.copyTo(it) } }
        } catch (e: Exception) {
            file.delete() // no half-copied file left behind
            throw e
        }

        val durationMs = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(file.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)

        val entity = CallEntity(
            startedAt = now,
            endedAt = now + durationMs,
            incoming = false,
            number = null,
            contactName = name.substringBeforeLast('.'),
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
}
