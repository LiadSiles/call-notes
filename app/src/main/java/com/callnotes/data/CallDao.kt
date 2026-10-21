package com.callnotes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CallDao {

    @Query("SELECT * FROM calls ORDER BY startedAt DESC")
    abstract fun observeAll(): Flow<List<CallEntity>>

    /** Calls with the first line of their transcript, for the list. */
    @Query(
        "SELECT calls.*, (SELECT text FROM segments WHERE segments.callId = calls.id " +
            "ORDER BY idx LIMIT 1) AS preview FROM calls ORDER BY startedAt DESC"
    )
    abstract fun observeList(): Flow<List<CallListItem>>

    @Query("SELECT * FROM calls WHERE id = :id")
    abstract fun observe(id: Long): Flow<CallEntity?>

    @Query("SELECT * FROM calls WHERE id = :id")
    abstract suspend fun get(id: Long): CallEntity?

    @Insert
    abstract suspend fun insert(call: CallEntity): Long

    @Query("DELETE FROM calls WHERE id = :id")
    abstract suspend fun deleteCall(id: Long)

    @Transaction
    open suspend fun delete(id: Long) {
        deleteSegments(id)
        deleteCall(id)
    }

    /** Newest recording still waiting for transcription. */
    @Query("SELECT * FROM calls WHERE status = 'recorded' AND filePath IS NOT NULL ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun nextPending(): CallEntity?

    @Query("SELECT COUNT(*) FROM calls WHERE status = 'recorded' AND filePath IS NOT NULL")
    abstract suspend fun pendingCount(): Int

    @Query("UPDATE calls SET status = :status, transcriptModel = :model, error = :error WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: String, model: String?, error: String?)

    /**
     * A call still "transcribing" when a run starts means the process died mid-call
     * (e.g. a native crash or out-of-memory). Mark it failed instead of retrying, so one
     * bad file can't crash the app on every launch; the user can retry it by hand.
     */
    @Query("UPDATE calls SET status = 'transcribe_failed', error = 'התהליך נעצר באמצע (ייתכן שחסר זיכרון)' WHERE status = 'transcribing'")
    abstract suspend fun failInterrupted()

    @Query("UPDATE calls SET status = 'transcribe_failed', error = :error WHERE status = 'transcribing'")
    abstract suspend fun failInterrupted(error: String)

    /** Changes the status and clears the error, keeping the model of an existing transcript. */
    @Query("UPDATE calls SET status = :status, error = NULL WHERE id = :id")
    abstract suspend fun setStatusOnly(id: Long, status: String)

    @Query("SELECT COUNT(*) FROM segments WHERE callId = :callId")
    abstract suspend fun segmentCount(callId: Long): Int

    /**
     * Where a call goes when its transcription is cancelled or interrupted: back to its
     * existing transcript if it has one (a re-transcription leaves the old one in place
     * until the new one is complete), else [otherwise].
     */
    @Transaction
    open suspend fun revertTranscription(id: Long, otherwise: String) {
        setStatusOnly(id, if (segmentCount(id) > 0) CallStatus.TRANSCRIBED else otherwise)
    }

    /** Cancels a call that is still waiting (not yet being transcribed). */
    @Transaction
    open suspend fun cancelWaiting(id: Long) {
        if (get(id)?.status == CallStatus.RECORDED) revertTranscription(id, CallStatus.HELD)
    }

    @Query("SELECT * FROM segments WHERE callId = :callId ORDER BY idx")
    abstract fun observeSegments(callId: Long): Flow<List<SegmentEntity>>

    @Insert
    abstract suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("DELETE FROM segments WHERE callId = :callId")
    abstract suspend fun deleteSegments(callId: Long)

    @Query("UPDATE calls SET speakerEmbeddings = :embeddings, meSpeaker = :me WHERE id = :id")
    abstract suspend fun setSpeakers(id: Long, embeddings: ByteArray?, me: Int?)

    @Query("UPDATE calls SET meSpeaker = :me WHERE id = :id")
    abstract suspend fun setMeSpeaker(id: Long, me: Int?)

    @Query("SELECT id, speakerEmbeddings FROM calls WHERE speakerEmbeddings IS NOT NULL")
    abstract suspend fun allSpeakerEmbeddings(): List<CallVoices>

    @Transaction
    open suspend fun replaceTranscript(callId: Long, segments: List<SegmentEntity>, model: String) {
        deleteSegments(callId)
        insertSegments(segments)
        setStatus(callId, CallStatus.TRANSCRIBED, model, null)
    }
}
