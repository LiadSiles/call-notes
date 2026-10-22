package com.callnotes.util

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Master switch: record calls at all. */
    var recordingEnabled: Boolean
        get() = sp.getBoolean("recording_enabled", true)
        set(value) = sp.edit().putBoolean("recording_enabled", value).apply()

    /** Also record internet calls (WhatsApp, Telegram, Meet…). Experimental. */
    var recordVoip: Boolean
        get() = sp.getBoolean("record_voip", true)
        set(value) = sp.edit().putBoolean("record_voip", value).apply()

    /**
     * Put each call on speakerphone. Off by default: on tested devices the mic
     * already captures both sides. Kept for devices where the far side is too quiet.
     */
    var autoSpeaker: Boolean
        get() = sp.getBoolean("auto_speaker_v2", false)
        set(value) = sp.edit().putBoolean("auto_speaker_v2", value).apply()

    /** Do nothing (record, transcribe, summarise) while the phone's battery saver is on. */
    var pauseInBatterySaver: Boolean
        get() = sp.getBoolean("pause_battery_saver", false)
        set(value) = sp.edit().putBoolean("pause_battery_saver", value).apply()

    /** A notification on the morning a task is due. */
    var taskReminders: Boolean
        get() = sp.getBoolean("task_reminders", true)
        set(value) = sp.edit().putBoolean("task_reminders", value).apply()

    /** Transcribe each recording automatically once the model is ready. */
    var autoTranscribe: Boolean
        get() = sp.getBoolean("auto_transcribe", true)
        set(value) = sp.edit().putBoolean("auto_transcribe", value).apply()

    /** Download models only on Wi-Fi. */
    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi_only", true)
        set(value) = sp.edit().putBoolean("wifi_only", value).apply()

    /** Model being downloaded (ModelCatalog id), or null. */
    var downloadModelId: String?
        get() = sp.getString("download_model_id", null)
        set(value) = sp.edit().putString("download_model_id", value).apply()

    /** Human-readable result of the last transcription run, for the settings screen. */
    var lastProcessing: String?
        get() = sp.getString("last_processing", null)
        set(value) = sp.edit().putString("last_processing", value).apply()

    /**
     * What a transcription run is doing right now ("loading model", "call 12, chunk 3/9"…),
     * or null when no run is active. Written synchronously: if the process dies (out of
     * memory, native crash) the next launch reads where it stopped.
     */
    var processingStage: String?
        get() = sp.getString("processing_stage", null)
        set(value) { sp.edit().putString("processing_stage", value).commit() }

    /**
     * Puts the switches back to their defaults (recording on, internet calls on, speaker off,
     * auto-transcribe on, Wi-Fi-only downloads). Leaves the model, permissions and data alone.
     */
    fun resetSwitches() {
        sp.edit()
            .remove("recording_enabled").remove("record_voip").remove("auto_speaker_v2")
            .remove("auto_transcribe").remove("wifi_only").remove("pause_battery_saver").remove("task_reminders")
            .apply()
    }

    /**
     * Active model per role (see ModelCatalog). Only ever set to an installed model.
     * The analysis role keeps the pre-0.4 key, which held the user's Gemma choice.
     */
    fun modelFor(role: com.callnotes.inference.ModelRole): String? =
        if (role == com.callnotes.inference.ModelRole.ANALYZE) modelId
        else sp.getString("model_${role.name.lowercase()}", null)

    fun setModelFor(role: com.callnotes.inference.ModelRole, id: String) {
        if (role == com.callnotes.inference.ModelRole.ANALYZE) modelId = id
        else sp.edit().putString("model_${role.name.lowercase()}", id).apply()
    }

    /** Active model id (see ModelCatalog). Only ever set to an installed model. */
    var modelId: String?
        get() = sp.getString("model_id", null)
        set(value) = sp.edit().putString("model_id", value).apply()

    /** Model ids still to download after the current one (comma-separated), for packs. */
    var downloadQueue: String
        get() = sp.getString("download_queue", "") ?: ""
        set(value) = sp.edit().putString("download_queue", value).apply()

    /** Active DownloadManager id for the model, or -1. */
    var downloadId: Long
        get() = sp.getLong("download_id", -1L)
        set(value) = sp.edit().putLong("download_id", value).apply()
}
