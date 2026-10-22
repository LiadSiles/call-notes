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

    // --- Search (plain substring: also finds words with Hebrew prefixes, e.g. "מחיר" in "המחיר") ---

    @Query(
        "SELECT segments.callId AS callId, segments.idx AS segmentIdx, segments.text AS text, calls.startedAt AS startedAt, " +
            "calls.contactName AS contactName, calls.number AS number, calls.kind AS kind, calls.incoming AS incoming " +
            "FROM segments JOIN calls ON calls.id = segments.callId " +
            "WHERE segments.text LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY calls.startedAt DESC, segments.idx LIMIT 300"
    )
    abstract suspend fun searchLines(q: String): List<SearchHit>

    @Query(
        "SELECT calls.id AS callId, NULL AS segmentIdx, " +
            "COALESCE((SELECT title FROM tasks WHERE tasks.callId = calls.id AND tasks.title LIKE '%' || :q || '%' ESCAPE '\\' LIMIT 1), " +
            "calls.summaryTitle, calls.contactName, calls.number, '') AS text, " +
            "calls.startedAt AS startedAt, calls.contactName AS contactName, calls.number AS number, " +
            "calls.kind AS kind, calls.incoming AS incoming FROM calls " +
            "WHERE calls.contactName LIKE '%' || :q || '%' ESCAPE '\\' OR calls.number LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR calls.analysisJson LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR EXISTS (SELECT 1 FROM tasks WHERE tasks.callId = calls.id AND tasks.title LIKE '%' || :q || '%' ESCAPE '\\') " +
            "ORDER BY calls.startedAt DESC LIMIT 100"
    )
    abstract suspend fun searchCalls(q: String): List<SearchHit>

    // --- Energy ---

    @Query("UPDATE calls SET transcribeEnergy = :percent, transcribeMs = :ms WHERE id = :id")
    abstract suspend fun setTranscribeCost(id: Long, percent: Double?, ms: Long)

    @Query("UPDATE calls SET analyzeEnergy = :percent, analyzeMs = :ms WHERE id = :id")
    abstract suspend fun setAnalyzeCost(id: Long, percent: Double?, ms: Long)

    // --- Retention ---

    @Query("SELECT * FROM calls WHERE filePath IS NOT NULL")
    abstract suspend fun withRecording(): List<CallEntity>

    @Query("UPDATE calls SET filePath = :path WHERE id = :id")
    abstract suspend fun setFilePath(id: Long, path: String)

    /** Transcribed calls whose recording is still an uncompressed WAV. */
    @Query("SELECT * FROM calls WHERE status = 'transcribed' AND filePath LIKE '%.wav' ORDER BY startedAt DESC LIMIT :limit")
    abstract suspend fun uncompressed(limit: Int): List<CallEntity>

    /** Transcribed (or failed-to-record) calls older than [before] that still have a recording. */
    @Query(
        "SELECT * FROM calls WHERE filePath IS NOT NULL AND startedAt < :before " +
            "AND status IN ('transcribed', 'record_failed')"
    )
    abstract suspend fun audioToExpire(before: Long): List<CallEntity>

    @Query("UPDATE calls SET filePath = NULL, audioDeletedAt = :at WHERE id = :id")
    abstract suspend fun markAudioDeleted(id: Long, at: Long)

    /** Calls older than [before] that aren't being transcribed and have no open task. */
    @Query(
        "SELECT * FROM calls WHERE startedAt < :before AND status != 'transcribing' " +
            "AND NOT EXISTS (SELECT 1 FROM tasks WHERE tasks.callId = calls.id AND tasks.done = 0)"
    )
    abstract suspend fun callsToExpire(before: Long): List<CallEntity>

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
    abstract suspend fun deleteTaskRows(callId: Long)

    /** Repeats in other calls of this call's tasks stand on their own again. */
    @Query("UPDATE tasks SET duplicateOf = NULL WHERE duplicateOf IN (SELECT id FROM tasks WHERE callId = :callId)")
    abstract suspend fun releaseRepeatsOf(callId: Long)

    @Transaction
    open suspend fun deleteTasks(callId: Long) {
        releaseRepeatsOf(callId)
        deleteTaskRows(callId)
    }

    /** Tasks in other calls marked as repeating one of this call's tasks, with that task's title. */
    @Query(
        "SELECT d.id AS id, o.title AS originalTitle FROM tasks d JOIN tasks o ON d.duplicateOf = o.id " +
            "WHERE o.callId = :callId"
    )
    abstract suspend fun repeatsOf(callId: Long): List<RepeatLink>

    @Transaction
    open suspend fun replaceAnalysis(callId: Long, json: String, title: String?, model: String, tasks: List<TaskEntity>) {
        deleteTasks(callId)
        insertTasks(tasks)
        setAnalysis(callId, json, title, model)
    }

    @Query("SELECT * FROM tasks WHERE callId = :callId ORDER BY idx")
    abstract fun observeTasks(callId: Long): Flow<List<TaskEntity>>

    /**
     * Ticks a task and the same task found in other calls (its duplicates, or the task it
     * duplicates), so one real-world task is done in one tap.
     */
    @Query(
        "UPDATE tasks SET done = :done WHERE id = :id OR duplicateOf = :id " +
            "OR id = (SELECT duplicateOf FROM tasks WHERE id = :id) " +
            "OR duplicateOf = (SELECT duplicateOf FROM tasks WHERE id = :id AND duplicateOf IS NOT NULL)"
    )
    abstract suspend fun setTaskDone(id: Long, done: Boolean)

    @Query("SELECT * FROM tasks WHERE id = :id")
    abstract suspend fun task(id: Long): TaskEntity?

    /** The next task whose due-date reminder hasn't been shown. */
    @Query(
        "SELECT * FROM tasks WHERE done = 0 AND duplicateOf IS NULL AND dueDate IS NOT NULL " +
            "AND remindedAt IS NULL ORDER BY dueDate LIMIT 1"
    )
    abstract suspend fun nextReminder(): TaskEntity?

    /** Tasks due on or before [today] (yyyy-MM-dd) that haven't been reminded yet. */
    @Query(
        "SELECT * FROM tasks WHERE done = 0 AND duplicateOf IS NULL AND dueDate IS NOT NULL " +
            "AND dueDate <= :today AND remindedAt IS NULL"
    )
    abstract suspend fun dueUnreminded(today: String): List<TaskEntity>

    @Query("UPDATE tasks SET remindedAt = :at WHERE id IN (:ids)")
    abstract suspend fun markReminded(ids: List<Long>, at: Long)

    /** Open, original tasks from other calls since [since], to spot repeats. */
    @Query(
        "SELECT * FROM tasks WHERE done = 0 AND duplicateOf IS NULL AND callId != :callId AND createdAt >= :since"
    )
    abstract suspend fun openTasksOtherCalls(callId: Long, since: Long): List<TaskEntity>

    @Query("UPDATE tasks SET duplicateOf = NULL WHERE id = :id")
    abstract suspend fun clearDuplicate(id: Long)

    @Query("UPDATE tasks SET duplicateOf = :of WHERE id = :id")
    abstract suspend fun setDuplicate(id: Long, of: Long)

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

    @Query("SELECT COUNT(*) FROM tasks WHERE done = 0 AND duplicateOf IS NULL")
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
