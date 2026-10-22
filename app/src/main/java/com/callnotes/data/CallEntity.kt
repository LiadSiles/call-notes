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
    /** The user cancelled its transcription: not picked up automatically; manual start only. */
    const val HELD = "held"
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

/** How a call is named in lists and notifications. */
object Caller {
    /** The contact's name, else the number, else what kind of call it was. */
    fun label(contactName: String?, number: String?, kind: String, incoming: Boolean): String =
        contactName ?: number ?: when (kind) {
            CallKind.VOIP -> "שיחת אינטרנט"
            CallKind.IMPORT -> "קובץ מיובא"
            else -> if (incoming) "שיחה נכנסת" else "שיחה יוצאת"
        }
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
    /**
     * Voice fingerprints of the two speakers found in the call (DB v5): 2 × dim floats,
     * little-endian, speaker 0 first. Lets the app tell "me" from the other side again
     * later (e.g. after the owner records a voice sample) without re-running anything.
     */
    val speakerEmbeddings: ByteArray? = null,
    /** Which speaker index is the owner, when known (DB v5). */
    val meSpeaker: Int? = null,
    /** [AnalysisStatus]; null = not analysed yet (DB v6). */
    val analysisStatus: String? = null,
    /** Summary, key points, decisions, follow-ups as JSON ([com.callnotes.inference.CallAnalysis]) (DB v6). */
    val analysisJson: String? = null,
    /** Short title from the analysis, shown in the call list (DB v6). */
    val summaryTitle: String? = null,
    val analysisModel: String? = null,
    val analysisError: String? = null,
    /** When the recording was deleted by the retention setting (DB v9); the text stays. */
    val audioDeletedAt: Long? = null,
    /** Battery percent used by transcription / summary (DB v10); null = unknown or charging. */
    val transcribeEnergy: Double? = null,
    val analyzeEnergy: Double? = null,
    /** How long transcription / summary took, ms (DB v10). */
    val transcribeMs: Long? = null,
    val analyzeMs: Long? = null,
)

object AnalysisStatus {
    const val DONE = "done"
    const val FAILED = "failed"
    /** Cancelled by the user; not picked up automatically again. */
    const val SKIPPED = "skipped"
}

/** A task found in a call (DB v6). */
@Entity(tableName = "tasks", indices = [Index("callId")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callId: Long,
    val idx: Int,
    val title: String,
    /** "me", "other" or "unknown". */
    val owner: String,
    /** ISO date (yyyy-MM-dd), already checked against the call date, or null. */
    val dueDate: String?,
    /** The words used in the call ("עד יום שלישי"), or null. */
    val duePhrase: String?,
    val quote: String?,
    val done: Boolean = false,
    /** 1-based transcript lines it came from, comma-separated (DB v7). */
    val lines: String? = null,
    /** False = the summariser wasn't sure, or the app couldn't verify it (DB v7). */
    val certain: Boolean = true,
    val createdAt: Long = 0,
    /** When its due-date reminder was shown, or null (DB v8). */
    val remindedAt: Long? = null,
    /** An open task from an earlier call that this one repeats, or null (DB v8). */
    val duplicateOf: Long? = null,
) {
    /** First source line as a segment index (0-based), or null. */
    val firstSegment: Int? get() = lines?.split(',')?.firstNotNullOfOrNull { it.trim().toIntOrNull() }?.minus(1)
}

/** A task with the call it came from, for the tasks screen. */
data class TaskListItem(
    @androidx.room.Embedded val task: TaskEntity,
    val callStartedAt: Long,
    val callContactName: String?,
    val callNumber: String?,
    val callKind: String,
    val callIncoming: Boolean,
)

/** One search match: a transcript line (segmentIdx set) or the call itself (summary, name, task). */
data class SearchHit(
    val callId: Long,
    val segmentIdx: Int?,
    val text: String,
    val startedAt: Long,
    val contactName: String?,
    val number: String?,
    val kind: String,
    val incoming: Boolean,
)

/** A task that repeats another call's task, and the title of the one it repeats. */
data class RepeatLink(val id: Long, val originalTitle: String)

/** One stretch of transcript with its position in the recording (DB v4). */
@Entity(tableName = "segments", indices = [Index("callId")])
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callId: Long,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Speaker index 0/1 from diarization, or null if speakers weren't separated (DB v5). */
    val speaker: Int? = null,
)

/** The stored voices of one call, for re-labelling "me" after a new voice sample. */
data class CallVoices(val id: Long, val speakerEmbeddings: ByteArray?)

/** A call row plus the first line of its transcript (null if none). */
data class CallListItem(
    @androidx.room.Embedded val call: CallEntity,
    val preview: String?,
)
