package com.callnotes.inference

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.callnotes.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Picks the model for this device, downloads it with the system DownloadManager (resumes
 * across network drops, shows its own progress notification), verifies the SHA-256 and
 * exposes the state to the UI.
 *
 * Models live in the app-specific external dir (`Android/data/<pkg>/files/models`):
 * DownloadManager can't write to internal storage, and on Android 11+ that directory is
 * private to the app. Removed on uninstall.
 */
class ModelManager(private val context: Context) {

    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Verifying : State
        data object Ready : State
        data class Failed(val message: String) : State
        /** Device below the minimum RAM for any model. */
        data object Unsupported : State
    }

    private val prefs = Prefs(context)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var verifying = false

    val modelsDir: File
        get() = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    val totalRamGb: Double
        get() {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            return info.totalMem / 1_000_000_000.0
        }

    /** Largest model the device can hold, or null if none. */
    fun recommended(): ModelSpec? = ModelCatalog.all.firstOrNull { totalRamGb >= it.minRamGb }

    /** The user's choice if set and still supported, else the recommendation. */
    fun selected(): ModelSpec? =
        ModelCatalog.byId(prefs.modelId)?.takeIf { totalRamGb >= it.minRamGb } ?: recommended()

    fun select(spec: ModelSpec) {
        prefs.modelId = spec.id
        refresh()
    }

    fun fileFor(spec: ModelSpec) = File(modelsDir, spec.fileName)
    private fun partFor(spec: ModelSpec) = File(modelsDir, spec.fileName + ".part")

    /** The verified model file, or null if not ready. */
    fun readyModel(): Pair<ModelSpec, File>? {
        val spec = selected() ?: return null
        val file = fileFor(spec)
        return if (file.exists() && file.length() == spec.sizeBytes) spec to file else null
    }

    fun hasSpaceFor(spec: ModelSpec): Boolean =
        modelsDir.usableSpace > spec.sizeBytes + 500L * 1024 * 1024

    fun startDownload(spec: ModelSpec, wifiOnly: Boolean) {
        cancelDownload()
        partFor(spec).delete()
        val request = DownloadManager.Request(Uri.parse(spec.downloadUrl))
            .setTitle("${spec.displayName} — מודל לתמלול")
            .setDescription("סיכומי שיחות")
            .setDestinationUri(Uri.fromFile(partFor(spec)))
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        prefs.modelId = spec.id
        prefs.downloadId = downloads.enqueue(request)
        _state.value = State.Downloading(0, spec.sizeBytes)
    }

    fun cancelDownload() {
        val id = prefs.downloadId
        if (id != -1L) downloads.remove(id)
        prefs.downloadId = -1L
    }

    fun delete(spec: ModelSpec) {
        cancelDownload()
        fileFor(spec).delete()
        partFor(spec).delete()
        refresh()
    }

    /** Re-reads the download / file state. Cheap; the UI calls it every second while downloading. */
    fun refresh() {
        val spec = selected()
        if (spec == null) { _state.value = State.Unsupported; return }
        if (verifying) return
        if (readyModel() != null) { _state.value = State.Ready; return }

        val id = prefs.downloadId
        if (id == -1L) {
            if (_state.value !is State.Failed) _state.value = State.NotDownloaded
            return
        }
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) {
                prefs.downloadId = -1L
                _state.value = State.NotDownloaded
                return
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> verify(spec)
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    prefs.downloadId = -1L
                    _state.value = State.Failed("ההורדה נכשלה (קוד $reason). נסה שוב.")
                }
                else -> _state.value = State.Downloading(done, spec.sizeBytes)
            }
        }
    }

    private fun verify(spec: ModelSpec) {
        verifying = true
        _state.value = State.Verifying
        scope.launch {
            val part = partFor(spec)
            val ok = part.exists() && part.length() == spec.sizeBytes && sha256(part) == spec.sha256
            prefs.downloadId = -1L
            if (ok && part.renameTo(fileFor(spec))) {
                _state.value = State.Ready
            } else {
                part.delete()
                _state.value = State.Failed("הקובץ שהורד פגום. נסה להוריד שוב.")
            }
            verifying = false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
