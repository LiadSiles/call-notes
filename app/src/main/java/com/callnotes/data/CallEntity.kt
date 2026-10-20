package com.callnotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Where a call stands in the pipeline. Later stages (transcription, analysis) extend this. */
object CallStatus {
    const val RECORDED = "recorded"
    const val RECORD_FAILED = "record_failed"
}

/** What happened when the app tried to put the call on speakerphone. */
object SpeakerResult {
    /** Speaker was on during the call (turned on by the app or by the user). */
    const val ON = "on"
    /** Every attempt failed; the user was asked to turn it on. */
    const val FAILED = "failed"
    /** A headset was connected, so the app left the audio route alone. */
    const val SKIPPED_HEADSET = "skipped_headset"
    /** Auto-speaker is turned off in settings. */
    const val DISABLED = "disabled"
    /** Not attempted: internet calls keep the calling app's own audio route. */
    const val NOT_APPLICABLE = "na"
}

/** What kind of call was recorded. */
object CallKind {
    /** Regular phone call (detected through telephony state). */
    const val CELLULAR = "cellular"
    /** Internet call — WhatsApp, Telegram, Meet… (detected from MODE_IN_COMMUNICATION). */
    const val VOIP = "voip"
}

@Entity(tableName = "calls")
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    /** Meaningless for [CallKind.VOIP] (direction unknown; stored as false). */
    val incoming: Boolean,
    val number: String?,
    val contactName: String?,
    /** Absolute path of the 16 kHz mono WAV file, or null if recording failed. */
    val filePath: String?,
    /** "voice_recognition" or "mic". */
    val audioSource: String?,
    /** Highest absolute sample value (0..32768). Near zero means the app received silence. */
    val peakLevel: Int,
    /** Android reported that it fed this recording silence (capture policy). */
    val silenced: Boolean,
    val speakerResult: String,
    /** "api", "user" or null — how the speaker got turned on. ("tap" in 0.1 data.) */
    val speakerMethod: String?,
    val status: String,
    /** [CallKind]; added in DB version 2, older rows are cellular. */
    val kind: String = CallKind.CELLULAR,
)
