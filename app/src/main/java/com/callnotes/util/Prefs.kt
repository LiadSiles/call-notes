package com.callnotes.util

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Master switch: record calls at all. */
    var recordingEnabled: Boolean
        get() = sp.getBoolean("recording_enabled", true)
        set(value) = sp.edit().putBoolean("recording_enabled", value).apply()

    /** Put each call on speakerphone so the microphone hears both sides. */
    var autoSpeaker: Boolean
        get() = sp.getBoolean("auto_speaker", true)
        set(value) = sp.edit().putBoolean("auto_speaker", value).apply()

    /**
     * If the audio API can't switch to speaker, tap the speaker button on the
     * in-call screen. Only touches the call screen that is already showing.
     */
    var tapSpeakerButton: Boolean
        get() = sp.getBoolean("tap_speaker_button", true)
        set(value) = sp.edit().putBoolean("tap_speaker_button", value).apply()
}
