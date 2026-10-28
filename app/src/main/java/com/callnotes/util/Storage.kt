package com.callnotes.util

import android.content.Context
import com.callnotes.CallNotesApp
import com.callnotes.inference.ModelCatalog
import com.callnotes.inference.ModelRole
import com.callnotes.inference.whisper.WhisperSpeed
import java.io.File

/**
 * What the app keeps on the phone, by kind ([measure], for the storage panel), and freeing
 * what isn't needed ([free]): only things nothing uses or that are made again on demand —
 * never recordings, transcripts, summaries or the models in use. [cleanLeftovers] runs at
 * start-up for temporary files a stopped run left behind.
 */
object Storage {

    /** One kind of content and its size. */
    data class Part(val label: String, val bytes: Long)

    /** Something that can go without losing anything of the user's. */
    data class Item(val label: String, val files: List<File>) {
        val bytes: Long get() = files.sumOf { it.length() }
    }

    data class Report(
        val parts: List<Part>,
        val total: Long,
        /** The largest files, with their path inside the app's folders. */
        val biggest: List<Pair<String, Long>>,
        val freeable: List<Item>,
    )

    private val WHISPER_COPY = Regex(""".*\.(q\d_\d)\.bin$""")

    /** Walks every folder of the app (private storage and its folder on shared storage). */
    fun measure(context: Context): Report {
        val roots = roots(context)
        val files = roots.flatMap { root ->
            // dataDir/lib links to the installed APK's libraries: not the app's data.
            root.walkTopDown().onEnter { !(it.name == "lib" && it.parentFile == root) }.filter { it.isFile }.map { root to it }
        }
        val app = context.applicationContext as CallNotesApp
        val models = app.models
        val modelsDir = models.modelsDir.canonicalFile
        val cacheDirs = listOfNotNull(context.cacheDir, context.externalCacheDir).map { it.canonicalFile }
        val activeIds = ModelRole.entries.mapNotNull { models.active(it)?.id }.toSet()
        val activeAnalyzer = models.active(ModelRole.ANALYZE)?.let { models.fileFor(it).nameWithoutExtension }
        val whisperBase = models.active(ModelRole.TRANSCRIBE)?.let { models.fileFor(it) }
        val currentCopy = whisperBase?.let { WhisperSpeed.current(Prefs(context)).fileFor(it).name }
        val downloading = Prefs(context).downloadModelId?.let { ModelCatalog.byId(it)?.fileName + ".part" }

        val sizes = LinkedHashMap<String, Long>()
        fun add(label: String, f: File) { sizes[label] = (sizes[label] ?: 0L) + f.length() }
        val unusedModels = mutableListOf<File>()
        val otherCopies = mutableListOf<File>()
        val staleCaches = mutableListOf<File>()
        val leftovers = mutableListOf<File>()

        for ((_, f) in files) {
            val c = f.canonicalFile
            val name = f.name
            when {
                c.startsWith(modelsDir) -> {
                    val spec = ModelCatalog.all.firstOrNull { it.fileName == name }
                    when {
                        name.endsWith(".part") -> {
                            if (name == downloading) add("הורדה בתהליך", f) else { add("שאריות", f); leftovers += f }
                        }
                        WHISPER_COPY.matches(name) -> {
                            add("עותקים מהירים של מודל התמלול", f)
                            if (name != currentCopy) otherCopies += f
                        }
                        spec != null && spec.id in activeIds -> add("מודלים בשימוש", f)
                        spec != null -> { add("מודלים שלא בשימוש", f); unusedModels += f }
                        else -> { add("שאריות", f); leftovers += f }
                    }
                }
                cacheDirs.any { c.startsWith(it) } -> {
                    // The model's working copy (made on first load, kept to load faster).
                    if (activeAnalyzer != null && name.startsWith(activeAnalyzer)) add("קבצי עבודה של מודל הסיכום", f)
                    else { add("קבצים זמניים", f); staleCaches += f }
                }
                c.path.contains("/recordings/") -> add("הקלטות שיחות", f)
                c.path.contains("/memos/") -> add("הקלטות משימה", f)
                c.path.contains("/databases/") -> add("תמלולים, סיכומים ומשימות", f)
                else -> add("אחר", f)
            }
        }
        val prefix = roots.associateWith { it.canonicalPath }
        val biggest = files.sortedByDescending { it.second.length() }.take(8).map { (root, f) ->
            ("…" + f.canonicalPath.removePrefix(prefix[root] ?: "")) to f.length()
        }
        val freeable = listOfNotNull(
            Item("מודלים שלא בשימוש", unusedModels).takeIf { unusedModels.isNotEmpty() },
            Item("עותקים מהירים שלא בשימוש (ייווצרו שוב אם תבחר מהירות אחרת)", otherCopies).takeIf { otherCopies.isNotEmpty() },
            Item("קבצים זמניים וקבצי עבודה ישנים", staleCaches).takeIf { staleCaches.isNotEmpty() },
            Item("שאריות של הורדות ועיבודים שנעצרו", leftovers).takeIf { leftovers.isNotEmpty() },
        )
        return Report(
            sizes.map { Part(it.key, it.value) }.sortedByDescending { it.bytes },
            files.sumOf { it.second.length() },
            biggest,
            freeable,
        )
    }

    /** Deletes [items]; returns the bytes freed. The model list is re-read (a model may be gone). */
    fun free(context: Context, items: List<Item>): Long {
        val freed = items.flatMap { it.files }.sumOf { f ->
            val size = f.length()
            if (f.delete()) size else 0L
        }
        (context.applicationContext as CallNotesApp).models.refresh()
        return freed
    }

    /**
     * At start-up, before anything runs: audio converted for a transcription that never
     * finished, and half-written fast copies of the transcription model.
     */
    fun cleanLeftovers(context: Context) {
        context.cacheDir.listFiles { f -> f.name.startsWith("convert_") && f.name.endsWith(".wav") }?.forEach { it.delete() }
        // The models folder (as ModelManager.modelsDir, without starting the model manager).
        val modelsDir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        modelsDir.listFiles { f -> f.name.endsWith(".part") && WHISPER_COPY.matches(f.name.removeSuffix(".part")) }
            ?.forEach { it.delete() }
    }

    private fun roots(context: Context): List<File> = listOfNotNull(
        context.dataDir,
        // Android/data/com.callnotes: the models (DownloadManager can only write there).
        context.getExternalFilesDir(null)?.parentFile,
    ).filter { it.isDirectory }

    /** "850 MB", "3.6 GB". */
    fun label(bytes: Long): String =
        if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%d MB".format((bytes / 1_000_000L).coerceAtLeast(if (bytes > 0) 1 else 0))
}
