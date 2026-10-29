package com.callnotes.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.callnotes.CallNotesApp
import com.callnotes.inference.ModelRole
import com.callnotes.processing.ProcessingLog
import com.callnotes.processing.TranscriptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app as a share target for audio ("שיתוף" in WhatsApp on a voice message, or in a
 * recorder or file manager). Shows no screen: copies the file(s) in, puts each in the queue
 * to be transcribed and then summarised into tasks, says so in a short message and closes —
 * the user stays where they were. Progress shows in the notification shade, and each new
 * task gets its notification when it is ready.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated after the work already ran (it isn't, with configChanges — just in case).
        if (savedInstanceState != null) { finish(); return }
        val uris = sharedAudio(intent)
        if (uris.isEmpty()) {
            toast("לא נמצאה הקלטה בשיתוף")
            finish()
            return
        }
        val app = application as CallNotesApp
        // The read permission for the shared file lasts while this screen is open: copy first.
        lifecycleScope.launch {
            val ids = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { Importer.import(this@ShareActivity, uri) }
                        .onFailure { ProcessingLog.write(this@ShareActivity, "shared file not imported: ${it.javaClass.simpleName}: ${it.message}") }
                        .getOrNull()
                }
            }
            val canTranscribe = app.models.readyModel() != null
            val canSummarise = app.models.readyModel(ModelRole.ANALYZE) != null
            ids.forEach { TranscriptionService.start(this@ShareActivity, callId = it) }
            toast(
                when {
                    ids.isEmpty() -> "לא ניתן לקרוא את הקובץ כהקלטה"
                    !canTranscribe -> "ההקלטה נשמרה. כדי להפיק ממנה משימות צריך להוריד מודל תמלול בהגדרות."
                    !canSummarise -> "ההקלטה נשמרת ומתומללת. כדי להפיק משימות צריך להוריד את Gemma בהגדרות."
                    ids.size < uris.size -> "מפיק משימות מ-${ids.size} מתוך ${uris.size} הקבצים (השאר אינם הקלטות)"
                    ids.size == 1 -> "מפיק משימות מההקלטה. אפשר לעקוב בשורת ההתראות."
                    else -> "מפיק משימות מ-${ids.size} הקלטות. אפשר לעקוב בשורת ההתראות."
                }
            )
            finish()
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    /** The audio files in a SEND / SEND_MULTIPLE intent (EXTRA_STREAM, else its clip data). */
    private fun sharedAudio(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val clips = intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
        // content:// only (a file:// path from another app is not readable), and not our own
        // shared files handed back to us.
        return (streams.ifEmpty { clips }).distinct().filter { it.scheme == "content" && it.authority != "$packageName.files" }
    }
}
