package com.callnotes.processing

import com.callnotes.audio.AudioCompressor
import com.callnotes.audio.AudioDecoder
import com.callnotes.audio.Wav
import com.callnotes.data.CallEntity
import com.callnotes.data.CallKind
import com.callnotes.data.CallStatus
import com.callnotes.data.MemoState
import com.callnotes.data.SegmentEntity
import com.callnotes.data.SpeakerResult
import com.callnotes.data.VoiceMemoEntity
import com.callnotes.inference.ModelCatalog
import com.callnotes.inference.ModelSpec
import com.callnotes.inference.Transcriber
import com.callnotes.inference.Transcribers
import com.callnotes.inference.Transcript
import com.callnotes.inference.speakers.BackgroundSpeech
import com.callnotes.inference.speakers.LevelSpeakers
import com.callnotes.inference.speakers.SpeakerAligner
import com.callnotes.inference.speakers.SpeakerDecoder
import com.callnotes.inference.speakers.Voice
import com.callnotes.inference.speakers.VoiceClusters
import com.callnotes.inference.speakers.VoiceEmbedder
import com.callnotes.inference.speakers.VoiceProfile
import com.callnotes.inference.speakers.VoiceTrack
import com.callnotes.inference.whisper.WhisperNative
import com.callnotes.inference.whisper.WhisperPrompt
import com.callnotes.inference.whisper.WhisperSpeed
import com.callnotes.inference.whisper.WhisperTranscriber
import com.callnotes.processing.TranscriptionService.Companion.describe
import com.callnotes.processing.TranscriptionService.Companion.fingerprint
import com.callnotes.security.DataProtection
import com.callnotes.util.BatterySaver
import com.callnotes.util.CpuInfo
import com.callnotes.util.Energy
import com.callnotes.util.Prefs
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transcription queue: what the user asked for (voice notes for new tasks and calls), in the
 * order asked, then the newest recording waiting. One at a time — a request waits for the
 * call in progress to finish, it never stops it. Whisper stays loaded for the whole queue.
 * After each call, speakers are separated and the summary worker is woken.
 */
class TranscribeWorker(host: TranscriptionService) : Worker(host, Stage.TRANSCRIBE) {

    override val tag = "transcribe"
    @Volatile var delayFirst = false
    @Volatile private var currentRequested = false

    /** The call being worked on now was asked for by the user (not picked automatically). */
    val currentIsManual: Boolean get() = currentRequested
    @Volatile private var transcriber: Transcriber? = null
    private var embedder: VoiceEmbedder? = null
    private var embedderTried = false

    /** Preset the loaded model runs with (null until loaded). */
    @Volatile private var speed: WhisperSpeed? = null
    @Volatile private var preparing = false

    override fun abortNative() {
        if (preparing) WhisperSpeed.abort()
        transcriber?.abort()
    }
    override fun publish(state: ProcessingStatus.State) = ProcessingStatus.set(state)

    override suspend fun loop() {
        val ready = app.models.readyModel() ?: return
        step("start · ${ProcessingLog.deviceLine(host)}")
        if (delayFirst && ProcessingStatus.first(stage) == null) delay(6_000) // let a just-ended call land
        delayFirst = false
        dao.failInterrupted()
        var done = 0
        var failed = 0
        try {
            while (true) {
                val next = next() ?: break
                if (next is Item.Memo) {
                    if (!transcribeMemo(next.memo, ready.first, ready.second)) break
                    continue
                }
                val (call, requested) = next as Item.Call
                begin(call)
                currentRequested = requested
                waitWhileHot()
                if (transcriber == null) {
                    transcriber = load(ready.first, ready.second, "מתמלל: ${label(call)}", call.id) { why ->
                        dao.setStatus(call.id, CallStatus.TRANSCRIBE_FAILED, null, why)
                    } ?: break
                }
                // Cancelled while the model loaded?
                if (call.id in cancelled || dao.get(call.id)?.status != CallStatus.RECORDED) {
                    cancelled -= call.id; end(); continue
                }
                // Picked automatically, and the user asked for something while the model
                // loaded: that goes first (nothing had started; this call stays in the queue).
                if (!requested && ProcessingStatus.first(stage) != null) { end(); continue }
                val t = transcriber!!
                when (runItem { process(call, t) }) {
                    true -> {
                        done++
                        // A long call the user transcribed by hand: summarise it too (the
                        // automatic summary skips calls over the length limit).
                        val prefs = Prefs(host)
                        if (requested && prefs.autoSummarize && prefs.tooLongForAuto(call.endedAt - call.startedAt)) {
                            ProcessingStatus.requestSummary(call.id)
                        }
                        host.wakeSummary()
                    }
                    false -> failed++
                    null -> log("call ${call.id}: " + if (cancelled.remove(call.id)) "cancelled by the user" else "stopped, back to the queue")
                }
                end()
            }
            if (done + failed > 0) TranscriptionService.note(host, if (failed == 0) "תומללו $done" else "תומללו $done, נכשלו $failed")
            step("end · transcribed $done, failed $failed")
            compressBacklog()
        } finally {
            transcriber?.close(); transcriber = null; speed = null
            embedder?.close(); embedder = null; embedderTried = false
            modelLoaded = false
            end()
            publish(ProcessingStatus.State())
        }
    }

    /**
     * Transcribes one voice note and hands its text to the summary worker. The audio is
     * deleted once the text is saved. Returns false if the model couldn't be loaded.
     */
    private suspend fun transcribeMemo(memo: VoiceMemoEntity, spec: ModelSpec, file: File): Boolean {
        memoId = memo.id
        currentRequested = true
        waitWhileHot()
        try {
            val title = "מתמלל הקלטת משימה"
            if (transcriber == null) {
                transcriber = load(spec, file, title, null, memo.id) { why ->
                    dao.setMemo(memo.id, MemoState.FAILED, memo.path, null, why)
                } ?: return false
            }
            if (memo.id in cancelledMemos) { cancelledMemos -= memo.id; return true }
            val t = transcriber!!
            val result = runItem {
                val started = System.currentTimeMillis()
                publish(ProcessingStatus.State(true, phase = Phase.MEMO_TRANSCRIBING, memoId = memo.id))
                host.showWork(this, title, "מתמלל…")
                try {
                    val audio = memo.path?.let(::File)?.takeIf { it.isFile } ?: error("recording missing")
                    val transcript = t.transcribe(audio, vocabulary = expectedWords(null)) { done, total ->
                        if (memo.id !in cancelledMemos) {
                            publish(ProcessingStatus.State(true, null, done, total, Phase.MEMO_TRANSCRIBING, memoId = memo.id))
                            host.showWork(this, title, if (total > 0) "${done * 100 / total}%" else "מתמלל…", done, total)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (memo.id in cancelledMemos) throw CancellationException("deleted")
                    val text = transcript.segments.joinToString(" ") { it.text.trim() }.trim()
                    withContext(NonCancellable) {
                        if (text.isEmpty()) {
                            dao.setMemo(memo.id, MemoState.NO_TASK, null, "", "לא נשמע דיבור בהקלטה")
                        } else {
                            dao.setMemo(memo.id, MemoState.TRANSCRIBED, null, text, null)
                        }
                        audio.delete()
                    }
                    // The note is the user's words: log only its size.
                    log("voice note ${memo.id}: transcribed in ${secs(started)} · ${memo.durationMs / 1000} s · ${text.length} chars")
                    true
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    log("voice note ${memo.id}: transcription FAILED: ${describe(e)}")
                    dao.setMemo(memo.id, MemoState.FAILED, memo.path, null,
                        "התמלול נכשל: ${e.javaClass.simpleName}")
                    false
                }
            }
            when (result) {
                // Its task is written next, keeping the note's place in the summary queue.
                true -> {
                    ProcessingStatus.enqueue(Request(Stage.SUMMARY, memoId = memo.id, at = memo.createdAt))
                    host.wakeSummary()
                }
                false -> Unit
                null -> log("voice note ${memo.id}: " + if (cancelledMemos.remove(memo.id)) "deleted by the user" else "stopped, stays queued")
            }
            return true
        } finally {
            memoId = null
            currentRequested = false
        }
    }

    /**
     * Requests first, in the order asked (voice notes and calls alike); then the newest
     * recording waiting. Null if nothing may run now (battery saver pausing).
     */
    private suspend fun next(): Item? {
        if (BatterySaver.paused(host)) return null
        takeRequest { r ->
            if (r.isMemo) dao.getMemo(r.memoId)?.takeIf { it.state == MemoState.RECORDED }?.let { Item.Memo(it) }
            else dao.get(r.callId)?.takeIf { it.filePath != null && it.status == CallStatus.RECORDED }?.let { Item.Call(it, true) }
        }?.let { return it }
        // The service also runs for a manual request or for summaries: with automatic
        // transcription off, or waiting for the charger, only requested calls are transcribed.
        if (!Energy.autoTranscribeAllowed(host)) return null
        return dao.nextPending(Prefs(host).autoMaxMs)?.let { Item.Call(it, false) }
    }

    /**
     * Loads Whisper (making the faster preset's copy first if needed). [title] and
     * [callId] / [memoId] say what it is loading for, in the notification and the UI;
     * [onFail] records a load failure on that item.
     */
    private suspend fun load(
        spec: ModelSpec,
        file: File,
        title: String,
        callId: Long?,
        memoId: Long? = null,
        onFail: suspend (String) -> Unit,
    ): Transcriber? {
        publish(ProcessingStatus.State(running = true, callId = callId, phase = Phase.LOADING, memoId = memoId))
        host.showWork(this, title, "טוען את מודל התמלול…")
        step("loading model ${spec.id} (${"%.2f".format(file.length() / 1e9)} GB) · ${ProcessingLog.freeRam(host)}")
        val started = System.currentTimeMillis()
        var preset = WhisperSpeed.current(Prefs(host))
        if (preset.quantType != null && !preset.fileFor(file).isFile) {
            val err = prepareVariant(file, preset, title, callId)
            if (err != null) {
                log("could not prepare the ${preset.suffix} model ($err); using the full model with this preset's decoding")
                TranscriptionService.note(host, "הכנת הגרסה המהירה נכשלה ($err). מתמלל עם המודל המלא.")
                preset = if (preset.beamSize == WhisperSpeed.ACCURATE.beamSize) WhisperSpeed.ACCURATE else preset
            }
        }
        val useQuant = preset.quantType != null && preset.fileFor(file).isFile
        val t = Transcribers.create(spec, file, preset)
        speed = preset
        step("preset ${preset.name}: ${if (useQuant) preset.fileFor(file).name else file.name}, beam ${preset.beamSize}")
        return try {
            t.load()
            modelLoaded = true
            val detail = (t as? WhisperTranscriber)?.let { " · ${it.threads} threads · ${WhisperNative.systemInfo().trim()}" } ?: ""
            log("model loaded in ${secs(started)} · ${ProcessingLog.freeRam(host)}$detail")
            t
        } catch (e: Throwable) {
            runCatching { t.close() }
            if (e is CancellationException) throw e
            log("model load FAILED after ${secs(started)}: ${describe(e)}")
            onFail("טעינת המודל נכשלה: ${e.javaClass.simpleName} ${e.message ?: ""}".take(300))
            TranscriptionService.note(host, "טעינת מודל התמלול נכשלה: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * With compression on: the call's WAV becomes an AAC .m4a once the transcript is saved.
     * Any failure keeps the WAV.
     */
    private suspend fun compress(callId: Long) {
        if (!Prefs(host).compressRecordings) return
        val call = dao.get(callId) ?: return
        val wav = call.filePath?.let(::File)?.takeIf { it.extension.equals("wav", true) && it.isFile } ?: return
        val started = System.currentTimeMillis()
        val wavBytes = wav.length()
        val m4a = File(wav.parentFile, wav.nameWithoutExtension + ".m4a")
        val out = withContext(Dispatchers.IO) { AudioCompressor.compress(wav, m4a) }
        if (out == null) { log("call $callId: compression failed, WAV kept"); return }
        withContext(NonCancellable) {
            dao.setFilePath(callId, out.absolutePath)
            wav.delete()
        }
        log("call $callId: compressed in ${secs(started)}: ${"%.1f".format(wavBytes / 1e6)} → ${"%.1f".format(out.length() / 1e6)} MB")
    }

    /** Older WAVs, a few per run, while the service is up anyway. */
    private suspend fun compressBacklog() {
        if (!Prefs(host).compressRecordings) return
        for (c in dao.uncompressed(5)) {
            if (!currentCoroutineContext().isActive || ProcessingStatus.first(stage) != null) return
            compress(c.id)
        }
    }

    /**
     * Makes the quantized copy for a faster preset, once (a minute or two). Shown as part of
     * this call's notification; cancel stops it and keeps nothing half-written.
     */
    private suspend fun prepareVariant(base: File, preset: WhisperSpeed, title: String, callId: Long?): String? {
        val started = System.currentTimeMillis()
        step("preparing ${preset.suffix} copy of ${base.name} · ${ProcessingLog.freeRam(host)}")
        publish(ProcessingStatus.State(running = true, callId = callId, phase = Phase.PREPARING_MODEL))
        host.showWork(this, title, "מכין גרסה מהירה של מודל התמלול (פעם אחת)…")
        preparing = true
        val err = try {
            withContext(Dispatchers.IO) {
                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                coroutineScope {
                    val watcher = launch {
                        try { awaitCancellation() } finally { if (!finished.get()) WhisperSpeed.abort() }
                    }
                    try {
                        WhisperSpeed.prepare(base, preset) { pct ->
                            publish(ProcessingStatus.State(true, callId, pct, 100, Phase.PREPARING_MODEL))
                            host.showWork(this@TranscribeWorker, title, "מכין גרסה מהירה של מודל התמלול (פעם אחת)… $pct%", pct, 100)
                        }
                    } finally {
                        finished.set(true)
                        watcher.cancel()
                    }
                }
            }
        } finally {
            preparing = false
        }
        currentCoroutineContext().ensureActive()
        if (err == null) log("${preset.suffix} copy ready in ${secs(started)} (${"%.2f".format(preset.fileFor(base).length() / 1e9)} GB)")
        return err
    }

    /** Returns true if the call was transcribed. */
    private suspend fun process(call: CallEntity, transcriber: Transcriber): Boolean {
        dao.setStatusOnly(call.id, CallStatus.TRANSCRIBING)
        publish(ProcessingStatus.State(true, call.id, 0, 0, Phase.TRANSCRIBING))
        val title = "מתמלל: ${label(call)}"
        host.showWork(this, title, "מכין את האודיו…")
        val converted = File(host.cacheDir, "convert_${call.id}.wav")
        val started = System.currentTimeMillis()
        var plainTemp: File? = null
        val battery = Energy.read(host)
        try {
            val (source, temporary) = DataProtection.sourceFor(host, call.id)
                ?: error("recording missing")
            if (temporary) plainTemp = source
            val before = fingerprint(source)
            step("call ${call.id}: preparing audio (${source.extension}, ${"%.1f".format(source.length() / 1e6)} MB, sha256 $before)")
            val audio = AudioDecoder.ensureWorkingFormat(source, converted)
            val audioSec = (Wav.readInfo(audio)?.durationMs ?: 0L) / 1000
            log("call ${call.id}: $audioSec s of audio")
            var lastLogged = -1
            val asrStarted = System.currentTimeMillis()
            val words = expectedWords(call.contactName)
            if (words.isNotEmpty()) log("call ${call.id}: ${words.count { it == ',' } + 1} expected words given to Whisper")
            val transcript = transcriber.transcribe(audio, vocabulary = words) { done, total ->
                val pct = if (total > 0) done * 100 / total else 0
                if (pct / 10 != lastLogged / 10 || done == total) {
                    lastLogged = pct
                    step("call ${call.id}: $pct% after ${secs(started)}")
                }
                if (call.id !in cancelled) {
                    publish(ProcessingStatus.State(true, call.id, done, total, Phase.TRANSCRIBING))
                    host.showWork(this, title, "$pct%", done, total)
                }
            }
            currentCoroutineContext().ensureActive()
            val asrMs = System.currentTimeMillis() - asrStarted
            speed?.let { s ->
                if (audioSec > 0) Prefs(host).addSpeedSample(s.name, audioSec * 1000, asrMs)
                log("call ${call.id}: speech recognition ${"%.2f".format(asrMs / 1000.0 / audioSec.coerceAtLeast(1))}× call length (preset ${s.name})")
            }
            if (transcript.secondLook > 0) {
                log("call ${call.id}: second look at ${transcript.secondLook} stretch(es) of missed speech · " +
                    "%.1f s of words added".format(transcript.recoveredMs / 1000.0))
            }
            host.showWork(this, title, "מזהה מי אמר מה…")
            val speakers = separateSpeakers(call, audio, transcript)
            if (call.id in cancelled) throw CancellationException("cancelled")
            val lines = speakers?.lines ?: transcript.segments
            val rows = lines.mapIndexed { i, s ->
                SegmentEntity(callId = call.id, idx = i, startMs = s.startMs, endMs = s.endMs, text = s.text,
                    speaker = s.speaker, background = s.background)
            }
            dao.replaceTranscript(call.id, rows, transcriber.modelId)
            dao.setSpeakers(call.id, speakers?.let { Voice.pack(it.embeddings) }, speakers?.me)
            val after = fingerprint(source)
            log("call ${call.id}: done in ${secs(started)} ($audioSec s audio), ${rows.size} segments, " +
                "${transcript.words.size} words, ${rows.sumOf { it.text.length }} chars · recording " +
                if (after == before) "unchanged (sha256 $after)" else "CHANGED! $before → $after")
            val used = Energy.percentUsed(battery, Energy.read(host))
            val tookMs = System.currentTimeMillis() - started
            dao.setTranscribeCost(call.id, used, tookMs)
            if (used != null && audioSec > 0) speed?.let { Prefs(host).addEnergySample(it.name, audioSec * 1000, used) }
            log("call ${call.id}: battery " + (used?.let { "%.2f%%".format(it) } ?: "not measured (charging or no data)"))
            compress(call.id)
            DataProtection.applyToCall(host, call.id, force = true)
            return true
        } catch (e: Throwable) {
            if (e is CancellationException || call.id in cancelled) {
                // Cancelled by the user → held; paused or stopped → back to the queue.
                // Either way an existing transcript (re-transcription) comes back.
                val otherwise = if (call.id in cancelled) CallStatus.HELD else CallStatus.RECORDED
                withContext(NonCancellable) { dao.revertTranscription(call.id, otherwise) }
                throw if (e is CancellationException) e else CancellationException("cancelled")
            }
            log("call ${call.id}: FAILED after ${secs(started)}: ${describe(e)}")
            dao.setStatus(call.id, CallStatus.TRANSCRIBE_FAILED, null, "${e.javaClass.simpleName}: ${e.message ?: ""}".take(300))
            return false
        } finally {
            converted.delete()
            plainTemp?.delete()
        }
    }

    private class Speakers(val lines: List<Transcript.Segment>, val embeddings: Map<Int, FloatArray>, val me: Int?)

    /**
     * Splits the transcript by speaker with loudness and voice together ([SpeakerDecoder]),
     * decides which speaker is the owner, and sets background speech aside. Never fails
     * the transcription: on any error the transcript is kept without speakers.
     */
    private suspend fun separateSpeakers(call: CallEntity, audio: File, transcript: Transcript): Speakers? {
        val pack = app.models.allInstalled(ModelCatalog.SPEAKER_PACK)
        val started = System.currentTimeMillis()
        return try {
            step("call ${call.id}: separating speakers")
            publish(ProcessingStatus.State(true, call.id, 0, 0, Phase.SPEAKERS))
            val samples = Wav.readFloats(audio)
            val owner = VoiceProfile.load(host)
            val words = transcript.words
            val levels = DoubleArray(words.size) { LevelSpeakers.levelDb(samples, words[it]) }

            // A fingerprint per word, from short overlapping windows (voice model needed).
            val d = if (pack) embedder() else null
            val voices: List<FloatArray?> = if (d != null && words.isNotEmpty()) {
                val eq = LevelSpeakers.equalise(samples)
                val track = VoiceTrack.windows(words)
                val prints = track.map { r ->
                    currentCoroutineContext().ensureActive()
                    if (call.id in cancelled) throw CancellationException("cancelled")
                    val from = (r.first * 16).toInt().coerceIn(0, eq.size)
                    val to = (r.last * 16).toInt().coerceIn(from, eq.size)
                    d.embed(eq.copyOfRange(from, to))
                }
                VoiceTrack.perWord(words, track, prints)
            } else List(words.size) { null }

            val result = SpeakerDecoder.decode(words, levels, voices)
            if (result == null) {
                log("call ${call.id}: speakers: not separated in ${secs(started)} (${words.size} words, " +
                    "${voices.count { it != null }} with a voice${if (!pack) ", voice model not installed" else ""}) — one voice, or too little speech")
                return null
            }
            val speakerOn = call.speakerResult == SpeakerResult.ON
            val voiceSays = if (owner != null) Voice.whichIsMe(owner, result.centroids) else null
            val (me, why) = SpeakerDecoder.whoIsMe(
                result, speakerOn, voiceSays, firstSpeaker = result.labels.firstOrNull(),
                incoming = call.incoming.takeIf { call.kind == CallKind.CELLULAR },
            )

            // Background speech, on the word groups (a window's side = most of its words).
            val groups = VoiceClusters.windows(words)
            val sides = IntArray(groups.size) { k ->
                val w = groups[k]
                val ones = (w.firstWord..w.lastWord).count { result.labels[it] == 1 }
                if (ones * 2 > w.lastWord - w.firstWord + 1) 1 else 0
            }
            val groupVoices = groups.map { w ->
                val vs = (w.firstWord..w.lastWord).mapNotNull { voices[it] }
                if (vs.isEmpty() || w.durationMs < VoiceClusters.MIN_EMBED_MS) null else VoiceTrack.unit(VoiceTrack.mean(vs))
            }
            val aside = background(call, samples, groups, sides, groupVoices.takeIf { d != null }, me, owner)
            val lines = SpeakerAligner.align(transcript, LevelSpeakers.turns(words, result.labels), aside)

            log("call ${call.id}: speakers in ${secs(started)} · " +
                (result.level?.let { "loudness %.1f / %.1f dB (%.1f apart)".format(it.loudDb, it.quietDb, it.separationDb) } ?: "loudness not used") +
                " · " + (result.voiceSimilarity?.let { "voices %.2f alike".format(it) } ?: "voice not used") +
                " · speaker 1 share ${(result.shareOf1 * 100).toInt()}% · speaker ${if (speakerOn) "on" else "off"} · " +
                "me=${me ?: "?"} ($why)" +
                (if (owner != null) result.centroids.entries.joinToString("") { " (${it.key}: %.2f)".format(Voice.cosine(owner, it.value)) } else " (no voice sample)") +
                " · ${lines.size} lines")
            // Voices kept for re-labelling after a new voice sample — not for calls held to
            // the ear, where loudness decides and the earpiece voice is too distorted.
            val handset = !speakerOn && (result.level?.separationDb ?: 0.0) >= SpeakerDecoder.HANDSET_GAP_DB
            Speakers(lines, if (handset) emptyMap() else result.centroids, me)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log("call ${call.id}: speaker separation FAILED, keeping plain transcript: ${describe(e)}")
            null
        }
    }

    /**
     * Speech of people not on the call, as time ranges to set aside ([BackgroundSpeech]).
     * Empty when switched off, without fingerprints, or on any doubt; never fails the call.
     */
    private fun background(
        call: CallEntity,
        samples: FloatArray,
        windows: List<VoiceClusters.Window>,
        sides: IntArray,
        fingerprints: List<FloatArray?>?,
        me: Int?,
        owner: FloatArray?,
    ): List<LongRange> {
        if (fingerprints == null || !Prefs(host).filterBackground) return emptyList()
        return try {
            val levels = DoubleArray(windows.size) { BackgroundSpeech.levelDb(samples, windows[it]) }
            val r = BackgroundSpeech.find(windows, sides, levels, fingerprints, me, owner)
            log("call ${call.id}: background speech: " + (r.skipped?.let { "nothing set aside ($it)" }
                ?: "${r.count} windows, %.1f s of %.1f s set aside".format(r.backgroundMs / 1000.0, r.speechMs / 1000.0)) +
                if (r.keptAnswered > 0) " · ${r.keptAnswered} quiet voice(s) kept: answered by the other side" else "")
            BackgroundSpeech.ranges(windows, r)
        } catch (e: Exception) {
            log("call ${call.id}: background speech check FAILED, nothing set aside: ${describe(e)}")
            emptyList()
        }
    }

    /** Names and words Whisper is told to expect: the owner, the other side, the user's list. */
    private fun expectedWords(contactName: String?): String {
        val prefs = Prefs(host)
        return WhisperPrompt.build(prefs.ownerName, contactName, WhisperPrompt.parseList(prefs.vocabulary))
    }

    private fun embedder(): VoiceEmbedder? {
        if (!embedderTried) {
            embedderTried = true
            embedder = runCatching {
                VoiceEmbedder(app.models.fileFor(ModelCatalog.WESPEAKER_RESNET34), CpuInfo.inferenceThreads())
            }.onFailure { log("voice model failed to load: ${describe(it)}") }.getOrNull()
        }
        return embedder
    }
}
