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

    @Query("SELECT * FROM segments WHERE callId = :callId ORDER BY idx")
    abstract fun observeSegments(callId: Long): Flow<List<SegmentEntity>>

    @Insert
    abstract suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("DELETE FROM segments WHERE callId = :callId")
    abstract suspend fun deleteSegments(callId: Long)

    @Transaction
    open suspend fun replaceTranscript(callId: Long, segments: List<SegmentEntity>, model: String) {
        deleteSegments(callId)
        insertSegments(segments)
        setStatus(callId, CallStatus.TRANSCRIBED, model, null)
    }
}
