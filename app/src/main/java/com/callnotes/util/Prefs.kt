package com.callnotes.util

import android.content.Context
import android.content.SharedPreferences
import com.callnotes.inference.ModelRole
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * All settings, in one SharedPreferences file ("prefs"). Keys are part of the stored data:
 * never rename one without migrating it. Every property is cheap to read (no caching
 * needed); writes apply asynchronously except [processingStage].
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    // ---- Recording ---------------------------------------------------------------------

    /** Master switch: record calls at all. */
    var recordingEnabled by bool(KEY_RECORDING, true)

    /** Also record internet calls (WhatsApp, Telegram, Meet…). Experimental. */
    var recordVoip by bool(KEY_VOIP, true)

    /**
     * Put each call on speakerphone. Off by default: on tested devices the mic already
     * captures both sides. Kept for devices where the far side is too quiet.
     */
    var autoSpeaker by bool(KEY_SPEAKER, false)

    // ---- Processing --------------------------------------------------------------------

    /** Transcribe each recording automatically once the model is ready. */
    var autoTranscribe by bool(KEY_AUTO, true)

    /**
     * Summarise each transcript automatically (separate from transcription since 0.12).
     * Until first set it follows [autoTranscribe], which used to control both.
     */
    var autoSummarize: Boolean
        get() = sp.getBoolean(KEY_AUTO_SUMMARY, autoTranscribe)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SUMMARY, value).apply()

    /** Either automatic step is on (worth starting the background service for). */
    val anyAuto: Boolean get() = autoTranscribe || autoSummarize

    /**
     * Calls longer than this many minutes are not transcribed or summarised automatically
     * (the user starts them by hand); 0 = no limit.
     */
    var autoMaxMinutes by int(KEY_AUTO_MAX, 0)

    /** The last limit the user chose, restored when the limit is switched back on. */
    var autoMaxLastMinutes by int("auto_max_last_minutes", 30)

    /** [autoMaxMinutes] in ms for queries; 0 = no limit. */
    val autoMaxMs: Long get() = autoMaxMinutes * 60_000L

    /** True if [durationMs] is over the automatic-processing limit. */
    fun tooLongForAuto(durationMs: Long): Boolean = autoMaxMinutes > 0 && durationMs > autoMaxMs

    /** Whisper preset name: ACCURATE / BALANCED / FAST (see WhisperSpeed). */
    var transcribeSpeed by string("transcribe_speed", "ACCURATE")

    /** Do nothing (record, transcribe, summarise) while the phone's battery saver is on. */
    var pauseInBatterySaver by bool(KEY_SAVER, false)

    /** Transcribe and summarise only while the phone is plugged in. */
    var onlyWhileCharging by bool(KEY_CHARGING, false)

    /** Measured speed per preset: total call audio and total recognition time, in ms. */
    fun speedStats(preset: String): Pair<Long, Long> =
        sp.getLong("speed_audio_$preset", 0) to sp.getLong("speed_work_$preset", 0)

    fun addSpeedSample(preset: String, audioMs: Long, workMs: Long) {
        val (a, w) = speedStats(preset)
        sp.edit().putLong("speed_audio_$preset", a + audioMs).putLong("speed_work_$preset", w + workMs).apply()
    }

    /** Battery used per preset: summed percent (×1000) and the call audio it covered (ms). */
    fun energyStats(preset: String): Pair<Long, Long> =
        sp.getLong("energy_milli_$preset", 0) to sp.getLong("energy_audio_$preset", 0)

    fun addEnergySample(preset: String, audioMs: Long, percent: Double) {
        val (e, a) = energyStats(preset)
        sp.edit().putLong("energy_milli_$preset", e + (percent * 1000).toLong())
            .putLong("energy_audio_$preset", a + audioMs).apply()
    }

    /** Human-readable result of the last processing run, for the settings screen. */
    var lastProcessing by nullableString("last_processing")

    /**
     * What a processing run is doing right now, or null when none is active. Written
     * synchronously: if the process dies (out of memory, native crash) the next launch
     * reads where it stopped ([com.callnotes.processing.ProcessingLog.recoverIfDied]).
     */
    var processingStage: String?
        get() = sp.getString("processing_stage", null)
        @android.annotation.SuppressLint("ApplySharedPref") // must be on disk before a crash
        set(value) { sp.edit().putString("processing_stage", value).commit() }

    // ---- Tasks -------------------------------------------------------------------------

    /** A notification on the morning a task is due. */
    var taskReminders by bool(KEY_REMINDERS, true)

    // ---- Privacy and storage -----------------------------------------------------------

    /** Delete recordings of transcribed calls after this many days; 0 = never. */
    var audioRetentionDays by int(KEY_AUDIO_DAYS, 0)

    /** Delete whole calls (without open tasks) after this many days; 0 = never. */
    var callRetentionDays by int(KEY_CALL_DAYS, 0)

    /** Re-encode recordings as AAC after transcription (~8× smaller). */
    var compressRecordings by bool("compress_recordings", false)

    /** Extra encryption of the database and recordings (security/DataProtection). */
    var encryptData by bool("encrypt_data", false)

    /** Set when an encrypted database could not be opened and was set aside. */
    var dataKeyLost by bool("data_key_lost", false)

    /** Ask for biometrics / the phone's PIN to open the app (see ui/AppLock). */
    var appLock by bool("app_lock", false)

    // ---- Models ------------------------------------------------------------------------

    /** Download models only on Wi-Fi. */
    var wifiOnly by bool(KEY_WIFI, true)

    /**
     * Active model per role (ModelCatalog id). Only ever set to an installed model.
     * The analysis role keeps the pre-0.4 key "model_id", which held the user's Gemma choice.
     */
    fun modelFor(role: ModelRole): String? = sp.getString(modelKey(role), null)

    fun setModelFor(role: ModelRole, id: String) = sp.edit().putString(modelKey(role), id).apply()

    private fun modelKey(role: ModelRole) =
        if (role == ModelRole.ANALYZE) "model_id" else "model_${role.name.lowercase()}"

    /** Model being downloaded (ModelCatalog id), or null. */
    var downloadModelId by nullableString("download_model_id")

    /** Active DownloadManager id for the model, or -1. */
    var downloadId by long("download_id", -1L)

    /** Model ids still to download after the current one (comma-separated), for packs. */
    var downloadQueue by string("download_queue", "")

    /**
     * Puts the switches back to their defaults. Leaves models, permissions, the app lock,
     * encryption and all data alone.
     */
    fun resetSwitches() {
        sp.edit().apply { RESETTABLE.forEach(::remove) }.apply()
    }

    // ---- Delegates ---------------------------------------------------------------------

    private fun bool(key: String, default: Boolean) = pref({ getBoolean(key, default) }, { putBoolean(key, it) })
    private fun int(key: String, default: Int) = pref({ getInt(key, default) }, { putInt(key, it) })
    private fun long(key: String, default: Long) = pref({ getLong(key, default) }, { putLong(key, it) })
    private fun string(key: String, default: String) = pref({ getString(key, default) ?: default }, { putString(key, it) })
    private fun nullableString(key: String) = pref<String?>({ getString(key, null) }, { putString(key, it) })

    /** A property stored under one key; reads go to SharedPreferences, writes apply(). */
    private inline fun <T> pref(
        crossinline get: SharedPreferences.() -> T,
        crossinline put: SharedPreferences.Editor.(T) -> SharedPreferences.Editor,
    ) = object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = sp.get()
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = sp.edit().put(value).apply()
    }

    companion object {
        private const val KEY_RECORDING = "recording_enabled"
        private const val KEY_VOIP = "record_voip"
        private const val KEY_SPEAKER = "auto_speaker_v2"
        private const val KEY_AUTO = "auto_transcribe"
        private const val KEY_SAVER = "pause_battery_saver"
        private const val KEY_CHARGING = "only_while_charging"
        private const val KEY_REMINDERS = "task_reminders"
        private const val KEY_AUDIO_DAYS = "audio_retention_days"
        private const val KEY_CALL_DAYS = "call_retention_days"
        private const val KEY_WIFI = "wifi_only"
        private const val KEY_AUTO_MAX = "auto_max_minutes"
        private const val KEY_AUTO_SUMMARY = "auto_summarize"

        /** What "restore defaults" resets. */
        private val RESETTABLE = listOf(
            KEY_RECORDING, KEY_VOIP, KEY_SPEAKER, KEY_AUTO, KEY_WIFI,
            KEY_SAVER, KEY_CHARGING, KEY_REMINDERS, KEY_AUDIO_DAYS, KEY_CALL_DAYS, KEY_AUTO_MAX,
            KEY_AUTO_SUMMARY,
        )

        /** Range of the length limit in settings, in minutes. */
        const val AUTO_MAX_MIN = 1
        const val AUTO_MAX_SLIDER = 120
        const val AUTO_MAX_TYPED = 600
    }
}
