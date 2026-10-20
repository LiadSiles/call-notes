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

    /** Active model id (see ModelCatalog). Only ever set to an installed model. */
    var modelId: String?
        get() = sp.getString("model_id", null)
        set(value) = sp.edit().putString("model_id", value).apply()

    /** Active DownloadManager id for the model, or -1. */
    var downloadId: Long
        get() = sp.getLong("download_id", -1L)
        set(value) = sp.edit().putLong("download_id", value).apply()
}
