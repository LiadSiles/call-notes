package com.callnotes.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a call stands in the pipeline. Later stages (transcription, analysis) extend this. */
object CallStatus {
    /** Has audio; waiting for transcription. */
    const val RECORDED = "recorded"
    const val RECORD_FAILED = "record_failed"
    const val TRANSCRIBING = "transcribing"
    const val TRANSCRIBED = "transcribed"
    const val TRANSCRIBE_FAILED = "transcribe_failed"
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
    /** Audio file imported by the user (any format). */
    const val IMPORT = "import"
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
    /** The microphone foreground service was running (DB v3). False = may go silent on lock. */
    val foreground: Boolean = false,
    /** Model that produced the transcript (DB v4). */
    val transcriptModel: String? = null,
    /** Last processing error, shown to the user (DB v4). */
    val error: String? = null,
)

/** One stretch of transcript with its position in the recording (DB v4). */
@Entity(tableName = "segments", indices = [Index("callId")])
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callId: Long,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** A call row plus the first line of its transcript (null if none). */
data class CallListItem(
    @androidx.room.Embedded val call: CallEntity,
    val preview: String?,
)
