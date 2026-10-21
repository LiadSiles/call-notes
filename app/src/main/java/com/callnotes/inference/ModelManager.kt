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
 * Tracks which models are installed, which one is active, and the (single) download in
 * progress. Downloads use the system DownloadManager (resumes across network drops,
 * shows its own notification); a file is used only after its SHA-256 matches.
 *
 * The active model changes only when the user picks an installed model, or when a
 * download they started finishes and verifies — never just by tapping a model that
 * isn't on the phone (0.3.x bug).
 *
 * Models live in the app-specific external dir (`Android/data/<pkg>/files/models`):
 * DownloadManager can't write to internal storage; on Android 11+ the dir is private.
 */
class ModelManager(private val context: Context) {

    sealed interface Download {
        data object Idle : Download
        data class Running(val spec: ModelSpec, val bytes: Long) : Download
        data class Verifying(val spec: ModelSpec) : Download
        data class Failed(val spec: ModelSpec, val message: String) : Download
    }

    data class Snapshot(
        val active: Map<ModelRole, ModelSpec>,
        val installed: Set<String>,
        val download: Download,
    ) {
        fun active(role: ModelRole): ModelSpec? = active[role]
        /** Transcription can run. */
        val ready get() = active[ModelRole.TRANSCRIBE] != null
    }

    private val prefs = Prefs(context)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(Snapshot(emptyMap(), emptySet(), Download.Idle))
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    @Volatile private var verifying: ModelSpec? = null

    init {
        // 0.3.x didn't record which model a download was for; drop such orphans.
        if (prefs.downloadId != -1L && prefs.downloadModelId == null) {
            downloads.remove(prefs.downloadId)
            prefs.downloadId = -1L
        }
        refresh()
    }

    val modelsDir: File
        get() = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    val totalRamGb: Double
        get() {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            return info.totalMem / 1_000_000_000.0
        }

    /** Best model for [role] the device can run, or null if none. */
    fun recommended(role: ModelRole): ModelSpec? =
        ModelCatalog.forRole(role).firstOrNull { totalRamGb >= it.minRamGb }

    /** Models for [role] worth offering on this device (supported, or already installed). */
    fun offered(role: ModelRole): List<ModelSpec> =
        ModelCatalog.forRole(role).filter { totalRamGb >= it.minRamGb || isInstalled(it) }

    fun fileFor(spec: ModelSpec) = File(modelsDir, spec.fileName)
    private fun partFor(spec: ModelSpec) = File(modelsDir, spec.fileName + ".part")

    fun isInstalled(spec: ModelSpec): Boolean {
        val f = fileFor(spec)
        return f.exists() && f.length() == spec.sizeBytes
    }

    /** The model used for [role]: the user's choice if installed, else any installed one. */
    fun active(role: ModelRole): ModelSpec? =
        ModelCatalog.byId(prefs.modelFor(role))?.takeIf { role in it.roles && isInstalled(it) }
            ?: ModelCatalog.forRole(role).firstOrNull { isInstalled(it) }

    /** The active model for [role] and its file, or null if none is installed. */
    fun readyModel(role: ModelRole = ModelRole.TRANSCRIBE): Pair<ModelSpec, File>? =
        active(role)?.let { it to fileFor(it) }

    /** Switches to an installed model. Does nothing for a model that isn't installed. */
    fun select(spec: ModelSpec) {
        if (!isInstalled(spec)) return
        spec.roles.forEach { prefs.setModelFor(it, spec.id) }
        refresh()
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
        prefs.downloadModelId = spec.id
        prefs.downloadId = downloads.enqueue(request)
        publish(Download.Running(spec, 0))
    }

    fun cancelDownload() {
        val id = prefs.downloadId
        if (id != -1L) downloads.remove(id)
        prefs.downloadId = -1L
        prefs.downloadModelId?.let { ModelCatalog.byId(it) }?.let { partFor(it).delete() }
        prefs.downloadModelId = null
        publish(Download.Idle)
    }

    fun delete(spec: ModelSpec) {
        if (prefs.downloadModelId == spec.id) cancelDownload()
        fileFor(spec).delete()
        partFor(spec).delete()
        refresh()
    }

    /** Re-reads files and the download. Cheap; the UI polls it while a download runs. */
    fun refresh() {
        val current = _state.value.download
        val spec = ModelCatalog.byId(prefs.downloadModelId)
        val id = prefs.downloadId
        val download: Download = when {
            verifying != null -> Download.Verifying(verifying!!)
            spec == null || id == -1L -> if (current is Download.Failed) current else Download.Idle
            else -> queryDownload(spec, id)
        }
        publish(download)
    }

    private fun queryDownload(spec: ModelSpec, id: Long): Download {
        val cursor = downloads.query(DownloadManager.Query().setFilterById(id))
            ?: return Download.Idle
        cursor.use { c ->
            if (!c.moveToFirst()) {
                prefs.downloadId = -1L; prefs.downloadModelId = null
                return Download.Idle
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> { verify(spec); Download.Verifying(spec) }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    prefs.downloadId = -1L; prefs.downloadModelId = null
                    Download.Failed(spec, "ההורדה נכשלה (קוד $reason). נסה שוב.")
                }
                else -> Download.Running(spec, done)
            }
        }
    }

    private fun verify(spec: ModelSpec) {
        if (verifying != null) return
        verifying = spec
        scope.launch {
            val part = partFor(spec)
            val ok = part.exists() && part.length() == spec.sizeBytes && sha256(part) == spec.sha256
            prefs.downloadId = -1L
            prefs.downloadModelId = null
            val result = if (ok && part.renameTo(fileFor(spec))) {
                spec.roles.forEach { prefs.setModelFor(it, spec.id) } // the user asked for it: switch
                Download.Idle
            } else {
                part.delete()
                Download.Failed(spec, "הקובץ שהורד פגום. נסה להוריד שוב.")
            }
            verifying = null
            publish(result)
        }
    }

    private fun publish(download: Download) {
        _state.value = Snapshot(
            active = ModelRole.entries.mapNotNull { r -> active(r)?.let { r to it } }.toMap(),
            installed = ModelCatalog.all.filter { isInstalled(it) }.map { it.id }.toSet(),
            download = download,
        )
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
