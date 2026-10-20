package com.callnotes.util

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Master switch: record calls at all. */
    var recordingEnabled: Boolean
        get() = sp.getBoolean("recording_enabled", true)
        set(value) = sp.edit().putBoolean("recording_enabled", value).apply()

    /**
     * Put each call on speakerphone. Off by default: on tested devices the mic
     * already captures both sides. Kept for devices where the far side is too quiet.
     */
    var autoSpeaker: Boolean
        get() = sp.getBoolean("auto_speaker_v2", false)
        set(value) = sp.edit().putBoolean("auto_speaker_v2", value).apply()
}
