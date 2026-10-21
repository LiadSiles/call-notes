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
        "SELECT calls.*, COALESCE(NULLIF(calls.summaryTitle, ''), (SELECT text FROM segments " +
            "WHERE segments.callId = calls.id ORDER BY idx LIMIT 1)) AS preview FROM calls ORDER BY startedAt DESC"
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
        deleteTasks(id)
        deleteCall(id)
    }

    // --- Analysis (summary + tasks) ---

    /** Newest transcribed call with words that hasn't been analysed yet. */
    @Query(
        "SELECT * FROM calls WHERE status = 'transcribed' AND analysisStatus IS NULL " +
            "AND EXISTS (SELECT 1 FROM segments WHERE segments.callId = calls.id) ORDER BY startedAt DESC LIMIT 1"
    )
    abstract suspend fun nextUnanalyzed(): CallEntity?

    @Query(
        "SELECT COUNT(*) FROM calls WHERE status = 'transcribed' AND analysisStatus IS NULL " +
            "AND EXISTS (SELECT 1 FROM segments WHERE segments.callId = calls.id)"
    )
    abstract suspend fun unanalyzedCount(): Int

    @Query("UPDATE calls SET analysisStatus = :status, analysisError = :error WHERE id = :id")
    abstract suspend fun setAnalysisStatus(id: Long, status: String?, error: String?)

    @Query(
        "UPDATE calls SET analysisStatus = 'done', analysisJson = :json, summaryTitle = :title, " +
            "analysisModel = :model, analysisError = NULL WHERE id = :id"
    )
    abstract suspend fun setAnalysis(id: Long, json: String, title: String?, model: String)

    @Insert
    abstract suspend fun insertTasks(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE callId = :callId")
    abstract suspend fun deleteTasks(callId: Long)

    @Transaction
    open suspend fun replaceAnalysis(callId: Long, json: String, title: String?, model: String, tasks: List<TaskEntity>) {
        deleteTasks(callId)
        insertTasks(tasks)
        setAnalysis(callId, json, title, model)
    }

    @Query("SELECT * FROM tasks WHERE callId = :callId ORDER BY idx")
    abstract fun observeTasks(callId: Long): Flow<List<TaskEntity>>

    @Query("UPDATE tasks SET done = :done WHERE id = :id")
    abstract suspend fun setTaskDone(id: Long, done: Boolean)

    @Query("SELECT * FROM tasks WHERE callId = :callId ORDER BY idx")
    abstract suspend fun tasksOf(callId: Long): List<TaskEntity>

    /** All tasks with their call: open first, then by due date (none last), newest first. */
    @Query(
        "SELECT tasks.*, calls.startedAt AS callStartedAt, calls.contactName AS callContactName, " +
            "calls.number AS callNumber, calls.kind AS callKind, calls.incoming AS callIncoming " +
            "FROM tasks JOIN calls ON calls.id = tasks.callId " +
            "ORDER BY tasks.done, CASE WHEN tasks.dueDate IS NULL THEN 1 ELSE 0 END, tasks.dueDate, " +
            "tasks.createdAt DESC, tasks.idx"
    )
    abstract fun observeAllTasks(): Flow<List<TaskListItem>>

    @Query("SELECT COUNT(*) FROM tasks WHERE done = 0")
    abstract fun observeOpenTaskCount(): Flow<Int>

    @Query("UPDATE segments SET speaker = :speaker WHERE id = :id")
    abstract suspend fun setSegmentSpeaker(id: Long, speaker: Int)

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

    /** A summary the user cancelled before it started isn't picked up automatically again. */
    @Query("UPDATE calls SET analysisStatus = 'skipped' WHERE id = :id AND status = 'transcribed' AND analysisStatus IS NULL")
    abstract suspend fun cancelAnalysisWaiting(id: Long)

    /** Cancels a call being transcribed right now: held, or back to its old transcript. */
    @Transaction
    open suspend fun cancelRunningTranscription(id: Long) {
        if (get(id)?.status == CallStatus.TRANSCRIBING) revertTranscription(id, CallStatus.HELD)
    }

    /** Cancels a call that is still waiting (not yet being transcribed). */
    @Transaction
    open suspend fun cancelWaiting(id: Long) {
        if (get(id)?.status == CallStatus.RECORDED) revertTranscription(id, CallStatus.HELD)
    }

    @Query("SELECT * FROM segments WHERE callId = :callId ORDER BY idx")
    abstract fun observeSegments(callId: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE callId = :callId ORDER BY idx")
    abstract suspend fun segments(callId: Long): List<SegmentEntity>

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

    /** A new transcript also makes the old summary stale: it is analysed again. */
    @Transaction
    open suspend fun replaceTranscript(callId: Long, segments: List<SegmentEntity>, model: String) {
        deleteSegments(callId)
        insertSegments(segments)
        setStatus(callId, CallStatus.TRANSCRIBED, model, null)
        clearAnalysis(callId)
    }

    @Query(
        "UPDATE calls SET analysisStatus = NULL, analysisJson = NULL, summaryTitle = NULL, " +
            "analysisModel = NULL, analysisError = NULL WHERE id = :id"
    )
    abstract suspend fun clearAnalysisFields(id: Long)

    @Transaction
    open suspend fun clearAnalysis(id: Long) {
        deleteTasks(id)
        clearAnalysisFields(id)
    }
}
