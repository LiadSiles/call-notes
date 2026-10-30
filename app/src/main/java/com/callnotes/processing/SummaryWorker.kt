package com.callnotes.processing

import android.app.ActivityManager
import com.callnotes.CallNotesApp
import com.callnotes.data.AnalysisStatus
import com.callnotes.data.CallEntity
import com.callnotes.data.Caller
import com.callnotes.data.CallKind
import com.callnotes.data.CallStatus
import com.callnotes.data.MemoState
import com.callnotes.data.TaskEntity
import com.callnotes.data.TaskSource
import com.callnotes.data.VoiceMemoEntity
import com.callnotes.inference.AnalysisPrompt
import com.callnotes.inference.Analyzer
import com.callnotes.inference.CallContext
import com.callnotes.inference.CallSummarizer
import com.callnotes.inference.GemmaAnalyzer
import com.callnotes.inference.ModelRole
import com.callnotes.inference.ModelSpec
import com.callnotes.inference.TaskMatcher
import com.callnotes.inference.TaskPrompt
import com.callnotes.inference.TranscriptText
import com.callnotes.inference.whisper.WhisperPrompt
import com.callnotes.processing.TranscriptionService.Companion.describe
import com.callnotes.util.BatterySaver
import com.callnotes.util.Energy
import com.callnotes.util.Notifications
import com.callnotes.util.Prefs
import com.callnotes.util.TaskReminders
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Summary queue, running alongside transcription: what the user asked for (voice notes
 * becoming tasks and calls to summarise), in the order asked, then (with automatic
 * processing on) the newest transcribed call without a summary. One at a time; a request
 * waits for the summary in progress. While the transcription worker is still busy it waits
 * for the next finished transcript instead of exiting, so Gemma stays loaded. Before loading
 * Gemma next to Whisper it checks memory.
 */
class SummaryWorker(host: TranscriptionService) : Worker(host, Stage.SUMMARY) {

    override val tag = "summary"
    @Volatile private var analyzer: Analyzer? = null
    /** The loaded model reasons before answering (setting read when it is loaded). */
    private var thinking = false
    @Volatile private var currentRequested = false
    /** File name of the loaded model (the memory breakdown in the log names it). */
    private var modelFile: String? = null
    /** The last summary was stopped for memory: unload Gemma before the next one. */
    private var unloadAfter = false

    /** The call being summarised now was asked for by the user (not picked automatically). */
    val currentIsManual: Boolean get() = currentRequested

    /** Poked when a transcription finishes or a summary is requested. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    override fun abortNative() { analyzer?.abort() }
    override fun publish(state: ProcessingStatus.State) = ProcessingStatus.setSummary(state)

    override suspend fun loop() {
        val (spec, file) = app.models.readyModel(ModelRole.ANALYZE) ?: return
        var done = 0
        var failed = 0
        try {
            while (true) {
                val next = next()
                if (next is Item.Memo) {
                    if (!memoToTasks(next.memo, spec, file)) break
                    unloadIfStopped()
                    continue
                }
                if (next == null) {
                    // More transcripts may still come: wait for one rather than unloading Gemma.
                    if (host.transcribe.running) {
                        publish(ProcessingStatus.State())
                        host.hideWork(this)
                        withTimeoutOrNull(5_000) { wake.receive() }
                        continue
                    }
                    break
                }
                val (call, requested) = next as Item.Call
                begin(call)
                currentRequested = requested
                waitWhileHot()
                if (analyzer == null) {
                    waitForMemory("מסכם: ${label(call)}", call.id)
                    analyzer = load(spec, file, "מסכם: ${label(call)}", call.id) { why ->
                        dao.setAnalysisStatus(call.id, AnalysisStatus.FAILED, why)
                    } ?: break
                }
                if (call.id in cancelled) { cancelled -= call.id; end(); continue }
                // Picked automatically, and the user asked for something while Gemma loaded: that goes first.
                if (!requested && ProcessingStatus.first(stage) != null) { end(); continue }
                val a = analyzer!!
                val outcome = runItem { analyse(call, a) }
                when (outcome) {
                    true -> done++
                    false -> failed++
                    null -> {
                        if (cancelled.remove(call.id)) {
                            withContext(NonCancellable) { dao.setAnalysisStatus(call.id, AnalysisStatus.SKIPPED, null) }
                            log("call ${call.id}: summary cancelled by the user")
                        }
                    }
                }
                // It ended inside this process: earlier deaths of the app on it no longer count.
                if (outcome != null) Prefs(host).clearSummaryDeaths(call.id)
                unloadIfStopped()
                end()
            }
            if (done + failed > 0) TranscriptionService.note(host, if (failed == 0) "סוכמו $done" else "סוכמו $done, נכשלו $failed")
        } finally {
            analyzer?.close(); analyzer = null
            modelLoaded = false
            end()
            publish(ProcessingStatus.State())
        }
    }

    /** After an answer stopped for memory: unload Gemma (its pages too); the next item loads it again. */
    private fun unloadIfStopped() {
        if (!unloadAfter) return
        unloadAfter = false
        analyzer?.close(); analyzer = null
        modelLoaded = false
    }

    /**
     * Writes the task(s) of one transcribed voice note with Gemma and replaces the note with
     * them. Returns false if the model couldn't be loaded.
     */
    private suspend fun memoToTasks(memo: VoiceMemoEntity, spec: ModelSpec, file: java.io.File): Boolean {
        val title = "מכין משימה מההקלטה"
        memoId = memo.id
        currentRequested = true
        try {
            waitWhileHot()
            if (analyzer == null) {
                waitForMemory(title, null, memo.id)
                analyzer = load(spec, file, title, null, memo.id) { why ->
                    dao.setMemo(memo.id, MemoState.FAILED, null, memo.text, why)
                } ?: return false
            }
            if (memo.id in cancelledMemos) { cancelledMemos -= memo.id; return true }
            val a = analyzer!!
            val result = runItem {
                val started = System.currentTimeMillis()
                publish(ProcessingStatus.State(true, phase = Phase.MEMO_TASK, memoId = memo.id))
                host.showWork(this, title, "קורא את ההקלטה…")
                try {
                    val zone = ZoneId.systemDefault()
                    val recordedAt = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(memo.createdAt), zone)
                    val guard = MemoryGuard()
                    val answer = guard.watch(
                        onOver = { used ->
                            log("voice note ${memo.id}: memory ${MemoryGuard.gb(used)} GB (${MemoryGuard.breakdown(modelFile)}) — stopping before the phone closes the app")
                            a.abort()
                            unloadAfter = true
                        },
                    ) { a.ask(TaskPrompt.build(memo.text.orEmpty(), recordedAt)) }
                    if (memo.id in cancelledMemos) throw CancellationException("deleted")
                    val found = try {
                        TaskPrompt.parse(answer, recordedAt)
                    } catch (e: Exception) {
                        log("voice note ${memo.id}: answer not valid JSON (${answer.length} chars): ${e.javaClass.simpleName}")
                        throw e
                    }
                    withContext(NonCancellable) {
                        if (found.isEmpty()) {
                            dao.setMemo(memo.id, MemoState.NO_TASK, null, memo.text, null)
                        } else {
                            val now = System.currentTimeMillis()
                            val rows = found.mapIndexed { i, t ->
                                TaskEntity(callId = TaskEntity.NO_CALL, idx = i, title = t.title, owner = "me",
                                    dueDate = t.dueDate, duePhrase = t.duePhrase, quote = memo.text, certain = t.certain,
                                    createdAt = now, dueTime = t.dueTime, source = TaskSource.VOICE)
                            }
                            val ids = dao.memoToTasks(memo.id, rows)
                            // On screen the new task appears in the list; otherwise say so.
                            if (!CallNotesApp.onScreen) {
                                ids.zip(rows).forEach { (id, t) -> Notifications.newTask(host, t.copy(id = id), "מהקלטה") }
                            }
                            TaskReminders.plan(host)
                        }
                    }
                    log("voice note ${memo.id}: ${found.size} task(s) in ${secs(started)} · ${answer.length} chars out")
                    true
                } catch (e: Throwable) {
                    if (e is CancellationException || memo.id in cancelledMemos) throw (e as? CancellationException ?: CancellationException("deleted"))
                    log("voice note ${memo.id}: task FAILED: ${describe(e)}")
                    dao.setMemo(memo.id, MemoState.FAILED, null, memo.text,
                        "יצירת המשימה נכשלה: ${e.javaClass.simpleName}")
                    false
                }
            }
            if (result == null) log("voice note ${memo.id}: " + if (cancelledMemos.remove(memo.id)) "deleted by the user" else "stopped, stays queued")
            return true
        } finally {
            memoId = null
            currentRequested = false
            end()
        }
    }

    /** Requests first, in the order asked; then an automatic summary. Null if none may run now. */
    private suspend fun next(): Item? {
        if (BatterySaver.paused(host)) return null
        takeRequest { r ->
            if (r.isMemo) dao.getMemo(r.memoId)?.takeIf { it.state == MemoState.TRANSCRIBED }?.let { Item.Memo(it) }
            else dao.get(r.callId)?.takeIf { it.status == CallStatus.TRANSCRIBED }?.let { Item.Call(it, true) }
        }?.let { return it }
        // Automatic summaries: switched on, and not waiting for the charger.
        if (!Energy.autoSummaryAllowed(host)) return null
        return dao.nextUnanalyzed(Prefs(host).autoMaxMs)?.let { Item.Call(it, false) }
    }

    /**
     * Gemma's weights are memory-mapped (they can be paged out); the risk is the working
     * memory next to Whisper's ~2 GB. Wait while the phone reports low memory and Whisper
     * is loaded; give up waiting once transcription finishes.
     */
    private suspend fun waitForMemory(title: String, callId: Long?, memoId: Long? = null) {
        val am = host.getSystemService(ActivityManager::class.java)
        var logged = false
        while (host.transcribe.modelLoaded) {
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            if (!mem.lowMemory && mem.availMem > MIN_FREE_BYTES) return
            if (!logged) {
                logged = true
                log("waiting for memory before loading the summary model (${ProcessingLog.freeRam(host)})")
            }
            publish(ProcessingStatus.State(running = true, callId = callId, phase = Phase.WAITING_MEMORY, memoId = memoId))
            host.showWork(this, title, "ממתין לזיכרון פנוי (מתמלל במקביל)…")
            delay(5_000)
        }
    }

    /** Loads Gemma; [title], [callId] / [memoId] name what for; [onFail] records a failure on it. */
    private suspend fun load(
        spec: ModelSpec,
        file: java.io.File,
        title: String,
        callId: Long?,
        memoId: Long? = null,
        onFail: suspend (String) -> Unit,
    ): Analyzer? {
        publish(ProcessingStatus.State(running = true, callId = callId, phase = Phase.LOADING_ANALYZER, memoId = memoId))
        host.showWork(this, title, "טוען את מודל הסיכום…")
        step("loading analysis model ${spec.id} · ${ProcessingLog.freeRam(host)} · ${workingCopy(file)}")
        val started = System.currentTimeMillis()
        thinking = Prefs(host).summaryThinking
        val g = GemmaAnalyzer(host, spec, file, thinking)
        modelFile = file.name
        return try {
            g.load()
            modelLoaded = true
            log("analysis model loaded in ${secs(started)} · ${ProcessingLog.freeRam(host)} · " +
                "thinking ${if (thinking) "on" else "off"}, context ${g.contextTokens} tokens")
            g
        } catch (e: Throwable) {
            runCatching { g.close() }
            if (e is CancellationException) throw e
            log("analysis model load FAILED: ${describe(e)}")
            onFail("טעינת מודל הסיכום נכשלה: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * LiteRT-LM keeps the weights prepared for this phone's CPU in the cache folder, named
     * after the model file with its time and size. Without it (first load, the system cleared
     * the cache, or the name no longer matches) it is made again during the first answer —
     * slower and with more memory — so the log says which case this load is.
     */
    private fun workingCopy(model: java.io.File): String {
        val copies = host.cacheDir.listFiles { f -> f.name.startsWith(model.name) }.orEmpty()
        val modified = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(java.util.Date(model.lastModified()))
        val wholeSecond = if (model.lastModified() % 1000 == 0L) ", whole second" else ""
        val copy = if (copies.isEmpty()) "no working copy (made during the first answer)"
            else "working copy " + copies.joinToString { "${MemoryGuard.gb(it.length())} GB" }
        return "$copy · model file modified $modified$wholeSecond"
    }

    private suspend fun analyse(call: CallEntity, analyzer: Analyzer): Boolean {
        val started = System.currentTimeMillis()
        val guard = MemoryGuard()
        val battery = Energy.read(host)
        val title = "מסכם: ${label(call)}"
        publish(ProcessingStatus.State(true, call.id, 0, 0, Phase.ANALYZING))
        host.showWork(this, title, "קורא את השיחה…")
        return try {
            val segments = dao.segments(call.id)
            val known = call.meSpeaker != null
            val other = Caller.otherSide(call)
            val imported = call.kind == CallKind.IMPORT
            // Background speech (people not on the call) is left out; lines keep their numbers.
            val lines = segments.filterNot { it.background }.map { s ->
                val who = when {
                    s.speaker == null -> ""
                    !known -> "דובר ${s.speaker + 1}"
                    s.speaker == call.meSpeaker -> "אני"
                    else -> other
                }
                AnalysisPrompt.Line(who, s.text, number = s.idx + 1)
            }
            val precise = Prefs(host).preciseSummary
            step("call ${call.id}: summarising ${if (precise) "precisely (3 questions per part)" else "quickly"} · " +
                "${lines.size} lines, ${lines.sumOf { it.text.length }} chars")
            var lastShown = 0L
            var doing = "קורא את השיחה…"
            val summarizer = CallSummarizer(analyzer, precise, thinking = thinking, listener = object : CallSummarizer.Listener {
                override fun step(step: CallSummarizer.Step, part: Int, parts: Int) {
                    val of = if (parts > 1) " · חלק $part מתוך $parts" else ""
                    doing = when (step) {
                        CallSummarizer.Step.NOTES -> "מסכם$of"
                        CallSummarizer.Step.TASKS -> "מחלץ משימות$of"
                        CallSummarizer.Step.REVIEW -> "בודק את המשימות$of"
                        CallSummarizer.Step.MERGE -> "מאחד את חלקי השיחה" + if (parts > 1) " ($part/$parts)" else ""
                    }
                    if (call.id !in cancelled) {
                        // Parts of the call as progress; merging is one last step.
                        val merging = step == CallSummarizer.Step.MERGE
                        publish(ProcessingStatus.State(true, call.id, if (merging) 0 else part, if (merging) 0 else parts, Phase.ANALYZING))
                        host.showWork(this@SummaryWorker, title, doing)
                    }
                }
                override fun chars(written: Int) {
                    val now = System.currentTimeMillis()
                    if (now - lastShown > 1_000 && call.id !in cancelled) {
                        lastShown = now
                        host.showWork(this@SummaryWorker, title, "$doing · $written תווים")
                    }
                }
                override fun log(text: String) = this@SummaryWorker.log("call ${call.id}: $text")
            })
            val result = guard.watch(
                onOver = { used ->
                    log("call ${call.id}: memory ${MemoryGuard.gb(used)} GB while \"$doing\" (${MemoryGuard.breakdown(modelFile)}) — stopping before the phone closes the app")
                    analyzer.abort()
                },
            ) { summarizer.summarize(
                lines,
                CallContext(
                    // An imported file's name is the file's, not a contact's.
                    call.startedAt, call.incoming, call.contactName.takeIf { !imported },
                    directionKnown = call.kind == CallKind.CELLULAR,
                    ownerName = Prefs(host).ownerName.trim().ifEmpty { null },
                    vocabulary = WhisperPrompt.parseList(Prefs(host).vocabulary),
                    imported = imported,
                ),
                ownerLabel = if (known) "אני" else null, otherLabel = if (known) other else null,
                zone = ZoneId.systemDefault(),
            ) }
            if (call.id in cancelled) throw CancellationException("cancelled")
            val now = System.currentTimeMillis()
            // Without knowing which speaker is the owner, whose task it is was only a guess: it
            // must not hide a task from the tasks screen as "the other side's". Owner unknown and
            // "לא בטוח" until the user says who is who (the call screen asks) and summarises again.
            // An imported recording with one voice (a voice message) is the exception: the
            // speaker is the other side, and the model is told how to read it.
            val ownerKnown = known || imported && segments.none { it.speaker != null }
            val tasks = result.tasks.mapIndexed { i, t ->
                TaskEntity(callId = call.id, idx = i, title = t.title, owner = if (ownerKnown) t.owner else "unknown",
                    dueDate = t.dueDate, duePhrase = t.duePhrase, quote = t.sourceQuote,
                    lines = t.lines.joinToString(",").ifEmpty { null },
                    certain = t.certain && ownerKnown, createdAt = now, dueTime = t.dueTime)
            }
            // Re-summarising shouldn't announce tasks the user has already seen (or ticked off).
            val existing = dao.tasksOf(call.id)
            val before = existing.associateBy { taskKey(it.title) }
            // Tasks the user edited stay as they are and stand for the summary's version of them.
            val edited = existing.filter { it.edited }
            val kept = tasks.filter { t -> edited.none { e -> sameTask(e, t) } }.map { t ->
                val old = before[taskKey(t.title)] ?: return@map t
                val sameTime = old.dueDate == t.dueDate && old.dueTime == t.dueTime
                t.copy(done = old.done, remindedAt = old.remindedAt.takeIf { sameTime },
                    snoozedUntil = old.snoozedUntil.takeIf { sameTime })
            }
            val laterRepeats = dao.repeatsOf(call.id) // re-summary: keep later calls pointing here
            dao.replaceAnalysis(call.id, AnalysisPrompt.toJson(result), result.title.ifBlank { null }, analyzer.modelId, kept)
            val saved = dao.tasksOf(call.id)
            laterRepeats.forEach { r ->
                saved.firstOrNull { taskKey(it.title) == taskKey(r.originalTitle) }
                    ?.let { dao.setDuplicate(r.id, it.id) }
            }
            val repeats = linkRepeats(call, saved)
            // Announced: the owner's own new tasks (what the other side took on is shown in the call).
            val fresh = saved.filter { taskKey(it.title) !in before && !it.done && it.id !in repeats && !it.othersSide }
            fresh.forEach { Notifications.newTask(host, it, label(call)) }
            TaskReminders.plan(host)
            val used = Energy.percentUsed(battery, Energy.read(host))
            dao.setAnalyzeCost(call.id, used, System.currentTimeMillis() - started)
            log("call ${call.id}: summary done in ${secs(started)} · ${summarizer.charsOut} chars out · " +
                "${result.keyPoints.size} points (${result.keyPoints.count { !it.certain }} unsure), " +
                "${tasks.size} tasks (model: ${result.tasks.count { it.owner == "me" }} mine, " +
                "${result.tasks.count { it.owner == TaskEntity.OTHER }} the other side's, " +
                "${result.tasks.count { it.owner != "me" && it.owner != TaskEntity.OTHER }} unknown; ${tasks.count { !it.certain }} unsure, " +
                "${fresh.size} new, ${repeats.size} repeats)${if (!ownerKnown) " · who is the owner unknown" else ""} · battery " +
                (used?.let { "%.2f%%".format(it) } ?: "not measured") + " · ${guard.summary()}")
            true
        } catch (e: Throwable) {
            if (e is CancellationException || call.id in cancelled) throw (e as? CancellationException ?: CancellationException("cancelled"))
            log("call ${call.id}: summary FAILED after ${secs(started)}: ${describe(e)} · ${guard.summary()}")
            val why = if (e is MemoryGuard.TooMuchMemory) {
                unloadAfter = true
                "הסיכום נעצר: מודל הסיכום הגיע ל-${MemoryGuard.gb(e.bytes)} GB זיכרון, ומעל 6 GB הטלפון סוגר את האפליקציה (וגם מכבה את הנגישות). אפשר לנסות שוב ממסך השיחה."
            } else {
                "${e.javaClass.simpleName}: ${e.message ?: ""}".take(300)
            }
            dao.setAnalysisStatus(call.id, AnalysisStatus.FAILED, why)
            false
        }
    }

    /**
     * Marks tasks that repeat an open task from an earlier call (last 60 days) as its
     * duplicate: shown once in the tasks screen, not announced again, ticked together.
     * Returns the ids marked.
     */
    private suspend fun linkRepeats(call: CallEntity, saved: List<TaskEntity>): Set<Long> {
        val since = call.startedAt - 60L * 86_400_000L
        val open = dao.openTasksOtherCalls(call.id, since).filter { it.createdAt <= System.currentTimeMillis() }
        if (open.isEmpty()) return emptySet()
        val candidates = open.map { TaskMatcher.Candidate(it.title, it.owner, it.dueDate) }
        val marked = mutableSetOf<Long>()
        for (t in saved) {
            if (t.done || t.edited || t.othersSide) continue
            val i = TaskMatcher.findOriginal(TaskMatcher.Candidate(t.title, t.owner, t.dueDate), candidates) ?: continue
            val original = open[i]
            // Only an earlier task can be the original, so two calls never point at each other:
            // one from an earlier call, or one the user added before this call.
            val originalTime = if (original.standalone) original.createdAt else dao.get(original.callId)?.startedAt ?: continue
            if (originalTime > call.startedAt) continue
            dao.setDuplicate(t.id, original.id)
            marked += t.id
        }
        return marked
    }

    private companion object {
        /** Free memory wanted before loading Gemma next to Whisper. */
        const val MIN_FREE_BYTES = 1_500_000_000L
    }
}

/** Task titles compared loosely (punctuation, niqqud, final letters), to match a re-summary's tasks. */
private fun taskKey(title: String) = TranscriptText.normalizeText(title)

/** A task the user edited is the same as a new summary's task with its title or quote. */
private fun sameTask(edited: TaskEntity, new: TaskEntity): Boolean =
    taskKey(edited.title) == taskKey(new.title) ||
        edited.quote != null && new.quote != null && taskKey(edited.quote) == taskKey(new.quote)
