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
        val ext = name.substringAfterLast('.', "audio").take(5)
        val now = System.currentTimeMillis()
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "import_$now.$ext")
        resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }

        val durationMs = try {
            MediaMetadataRetriever().run {
                setDataSource(file.absolutePath)
                val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                release()
                d
            }
        } catch (e: Exception) {
            0L
        }

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
