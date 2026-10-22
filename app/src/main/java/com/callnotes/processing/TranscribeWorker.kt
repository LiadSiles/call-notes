package com.callnotes.processing

import com.callnotes.audio.AudioDecoder
import com.callnotes.audio.Wav
import com.callnotes.data.CallEntity
import com.callnotes.data.CallStatus
import com.callnotes.data.SegmentEntity
import com.callnotes.inference.InferenceEngine
import com.callnotes.inference.ModelCatalog
import com.callnotes.inference.ModelSpec
import com.callnotes.inference.Transcriber
import com.callnotes.inference.Transcribers
import com.callnotes.inference.Transcript
import com.callnotes.inference.speakers.LevelSpeakers
import com.callnotes.inference.speakers.SpeakerAligner
import com.callnotes.inference.speakers.Voice
import com.callnotes.inference.speakers.VoiceClusters
import com.callnotes.inference.speakers.VoiceEmbedder
import com.callnotes.inference.speakers.VoiceProfile
import com.callnotes.inference.whisper.WhisperNative
import com.callnotes.inference.whisper.WhisperSpeed
import com.callnotes.inference.whisper.WhisperTranscriber
import com.callnotes.processing.TranscriptionService.Companion.describe
import com.callnotes.processing.TranscriptionService.Companion.fingerprint
import com.callnotes.util.CpuInfo
import com.callnotes.util.Notifications
import com.callnotes.util.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transcription queue: manual requests first (a request pauses an automatic call, which
 * goes back to the queue), then the newest recording waiting. Whisper stays loaded for the
 * whole queue. After each call, speakers are separated and the summary worker is woken.
 */
class TranscribeWorker(host: TranscriptionService) : Worker(host, Notifications.ID_TRANSCRIBE) {

    override val tag = "transcribe"
    @Volatile var delayFirst = false
    @Volatile private var currentRequested = false
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

    /** Main thread. A manual request arrived: pause an automatic call on something else. */
    fun onRequest(id: Long) {
        if (running && !currentRequested && callId != null && callId != id) {
            // Not marked cancelled: the paused call goes back to the queue.
            abortNative()
            cancelItem()
        }
    }

    override suspend fun loop() {
        val ready = app.models.readyModel() ?: return
        step("start · ${ProcessingLog.deviceLine(host)}")
        if (delayFirst && ProcessingStatus.firstRequested() == null) delay(6_000) // let a just-ended call land
        delayFirst = false
        dao.failInterrupted()
        var done = 0
        var failed = 0
        try {
            while (true) {
                val (call, requested) = nextCall() ?: break
                waitWhileHot()
                begin(call)
                currentRequested = requested
                if (transcriber == null) {
                    transcriber = load(ready.first, ready.second, call) ?: break
                }
                // Cancelled while the model loaded? Skip it.
                if (call.id in cancelled || dao.get(call.id)?.status != CallStatus.RECORDED) {
                    cancelled -= call.id; end(); continue
                }
                val t = transcriber!!
                when (runItem { process(call, t) }) {
                    true -> { done++; host.wakeSummary() }
                    false -> failed++
                    null -> log("call ${call.id}: " + if (cancelled.remove(call.id)) "cancelled by the user" else "paused, a manual request goes first")
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

    private suspend fun nextCall(): Pair<CallEntity, Boolean>? {
        if (com.callnotes.util.BatterySaver.paused(host)) return null
        while (true) {
            val id = ProcessingStatus.firstRequested() ?: break
            ProcessingStatus.unrequest(id)
            val call = dao.get(id)
            if (call != null && call.filePath != null && call.status == CallStatus.RECORDED) return call to true
        }
        return dao.nextPending()?.let { it to false }
    }

    private suspend fun load(spec: ModelSpec, file: File, call: CallEntity): Transcriber? {
        publish(ProcessingStatus.State(running = true, callId = call.id, phase = "loading"))
        host.showWork(this, "מתמלל: ${label(call)}", "טוען את מודל התמלול…", callId = call.id)
        step("loading model ${spec.id} (${"%.2f".format(file.length() / 1e9)} GB) · ${ProcessingLog.freeRam(host)}")
        val started = System.currentTimeMillis()
        var preset = if (spec.engine == InferenceEngine.WHISPER_CPP) WhisperSpeed.current(Prefs(host)) else WhisperSpeed.ACCURATE
        if (preset.quantType != null && !preset.fileFor(file).isFile) {
            val err = prepareVariant(file, preset, call)
            if (err != null) {
                log("could not prepare the ${preset.suffix} model ($err); using the full model with this preset's decoding")
                TranscriptionService.note(host, "הכנת הגרסה המהירה נכשלה ($err). מתמלל עם המודל המלא.")
                preset = if (preset.beamSize == WhisperSpeed.ACCURATE.beamSize) WhisperSpeed.ACCURATE else preset
            }
        }
        val useQuant = preset.quantType != null && preset.fileFor(file).isFile
        val t = if (useQuant || preset == WhisperSpeed.ACCURATE) Transcribers.create(host, spec, file, preset)
            else WhisperTranscriber(spec, file, preset.beamSize) // FAST without its copy
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
            dao.setStatus(call.id, CallStatus.TRANSCRIBE_FAILED, null,
                "טעינת המודל נכשלה: ${e.javaClass.simpleName} ${e.message ?: ""}".take(300))
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
        val out = withContext(Dispatchers.IO) { com.callnotes.audio.AudioCompressor.compress(wav, m4a) }
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
            if (!currentCoroutineContext().isActive || ProcessingStatus.firstRequested() != null) return
            compress(c.id)
        }
    }

    /**
     * Makes the quantized copy for a faster preset, once (a minute or two). Shown as part of
     * this call's notification; cancel stops it and keeps nothing half-written.
     */
    private suspend fun prepareVariant(base: File, preset: WhisperSpeed, call: CallEntity): String? {
        val started = System.currentTimeMillis()
        val title = "מתמלל: ${label(call)}"
        step("preparing ${preset.suffix} copy of ${base.name} · ${ProcessingLog.freeRam(host)}")
        publish(ProcessingStatus.State(running = true, callId = call.id, phase = "preparing_model"))
        host.showWork(this, title, "מכין גרסה מהירה של מודל התמלול (פעם אחת)…", callId = call.id)
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
                            publish(ProcessingStatus.State(true, call.id, pct, 100, "preparing_model"))
                            host.showWork(this@TranscribeWorker, title, "מכין גרסה מהירה של מודל התמלול (פעם אחת)… $pct%", pct, 100, call.id)
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
        publish(ProcessingStatus.State(true, call.id, 0, 0, "transcribing"))
        val title = "מתמלל: ${label(call)}"
        host.showWork(this, title, "מכין את האודיו…", callId = call.id)
        val converted = File(host.cacheDir, "convert_${call.id}.wav")
        val started = System.currentTimeMillis()
        var plainTemp: File? = null
        try {
            val (source, temporary) = com.callnotes.security.DataProtection.sourceFor(host, call.id)
                ?: error("recording missing")
            if (temporary) plainTemp = source
            val before = fingerprint(source)
            step("call ${call.id}: preparing audio (${source.extension}, ${"%.1f".format(source.length() / 1e6)} MB, sha256 $before)")
            val audio = AudioDecoder.ensureWorkingFormat(source, converted)
            val audioSec = (Wav.readInfo(audio)?.durationMs ?: 0L) / 1000
            log("call ${call.id}: $audioSec s of audio")
            var lastLogged = -1
            val asrStarted = System.currentTimeMillis()
            val transcript = transcriber.transcribe(audio) { done, total ->
                val pct = if (total > 0) done * 100 / total else 0
                if (pct / 10 != lastLogged / 10 || done == total) {
                    lastLogged = pct
                    step("call ${call.id}: $pct% after ${secs(started)}")
                }
                if (call.id !in cancelled) {
                    publish(ProcessingStatus.State(true, call.id, done, total, "transcribing"))
                    host.showWork(this, title, "$pct%", done, total, call.id)
                }
            }
            currentCoroutineContext().ensureActive()
            val asrMs = System.currentTimeMillis() - asrStarted
            speed?.let { s ->
                if (audioSec > 0) Prefs(host).addSpeedSample(s.name, audioSec * 1000, asrMs)
                log("call ${call.id}: speech recognition ${"%.2f".format(asrMs / 1000.0 / audioSec.coerceAtLeast(1))}× call length (preset ${s.name})")
            }
            host.showWork(this, title, "מזהה מי אמר מה…", callId = call.id)
            val speakers = separateSpeakers(call, audio, transcript)
            if (call.id in cancelled) throw CancellationException("cancelled")
            val lines = speakers?.lines ?: transcript.segments
            val rows = lines.mapIndexed { i, s ->
                SegmentEntity(callId = call.id, idx = i, startMs = s.startMs, endMs = s.endMs, text = s.text, speaker = s.speaker)
            }
            dao.replaceTranscript(call.id, rows, transcriber.modelId)
            dao.setSpeakers(call.id, speakers?.let { Voice.pack(it.embeddings) }, speakers?.me)
            val after = fingerprint(source)
            log("call ${call.id}: done in ${secs(started)} ($audioSec s audio), ${rows.size} segments, " +
                "${transcript.words.size} words, ${rows.sumOf { it.text.length }} chars · recording " +
                if (after == before) "unchanged (sha256 $after)" else "CHANGED! $before → $after")
            compress(call.id)
            com.callnotes.security.DataProtection.applyToCall(host, call.id, force = true)
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
     * Splits the transcript by speaker: loudness first (calls held to the ear), voice
     * fingerprints when the levels are close (speakerphone, VoIP). Never fails the
     * transcription: on any error the transcript is kept without speakers.
     */
    private suspend fun separateSpeakers(call: CallEntity, audio: File, transcript: Transcript): Speakers? {
        val pack = app.models.allInstalled(ModelCatalog.SPEAKER_PACK)
        val started = System.currentTimeMillis()
        return try {
            step("call ${call.id}: separating speakers")
            publish(ProcessingStatus.State(true, call.id, 0, 0, "speakers"))
            val info = Wav.readInfo(audio) ?: return null
            val pcm = Wav.readSamples(audio, info, 0, info.sampleCount.toInt())
            val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
            val owner = VoiceProfile.load(host)

            val level = LevelSpeakers.analyse(samples, transcript.words)
            if (level != null) {
                var me = 0
                var check = if (owner == null) "no voice sample" else "voice sample not used"
                val d = if (owner != null && pack) embedder() else null
                if (owner != null && d != null) {
                    val voices = (0..1).mapNotNull { s ->
                        d.embed(LevelSpeakers.samplesOf(samples, transcript.words, level.labels, s))?.let { s to it }
                    }.toMap()
                    val who = Voice.whichIsMe(owner, voices)
                    // A wide level gap means the phone was at the owner's ear: louder IS the owner,
                    // and the earpiece leak's fingerprint is too distorted to overrule that. Only a
                    // narrow gap (speakerphone louder than the owner) lets the voice sample decide.
                    val trustVoice = level.separationDb < HANDSET_GAP_DB
                    if (who == 1 && trustVoice) me = 1
                    check = "voice sample says ${who ?: "?"}${if (trustVoice) "" else " (ignored: handset gap)"}" +
                        voices.entries.joinToString("") { " (${it.key}: %.2f)".format(Voice.cosine(owner, it.value)) }
                }
                val lines = SpeakerAligner.align(transcript, LevelSpeakers.turns(transcript.words, level.labels))
                log("call ${call.id}: speakers by loudness in ${secs(started)} · louder " +
                    "%.1f dB, quieter %.1f dB (%.1f dB apart), quieter share %d%% · me=%d · %s · %d lines".format(
                        level.loudDb, level.quietDb, level.separationDb, (level.quietShare * 100).toInt(), me, check, lines.size))
                return Speakers(lines, emptyMap(), me)
            }

            if (!pack) {
                log("call ${call.id}: speakers: loudness doesn't separate them and the voice model isn't installed")
                return null
            }
            val d = embedder() ?: return null
            val eq = LevelSpeakers.equalise(samples)
            val windows = VoiceClusters.windows(transcript.words)
            val embeddings = windows.map { w ->
                currentCoroutineContext().ensureActive()
                if (call.id in cancelled) throw CancellationException("cancelled")
                if (w.durationMs < VoiceClusters.MIN_EMBED_MS) null else {
                    val from = ((w.startMs - 50) * 16).toInt().coerceIn(0, eq.size)
                    val to = ((w.endMs + 50) * 16).toInt().coerceIn(from, eq.size)
                    d.embed(eq.copyOfRange(from, to))
                }
            }
            val result = VoiceClusters.cluster(windows, embeddings)
            if (result == null) {
                log("call ${call.id}: speakers by voice in ${secs(started)} · ${windows.size} windows, " +
                    "${embeddings.count { it != null }} fingerprinted · one voice or too little speech → no split")
                return null
            }
            val me = if (owner != null) Voice.whichIsMe(owner, result.centroids) else null
            val lines = SpeakerAligner.align(transcript, VoiceClusters.turns(windows, result.labels))
            log("call ${call.id}: speakers by voice in ${secs(started)} · ${windows.size} windows, " +
                "${embeddings.count { it != null }} fingerprinted · voices %.2f alike · speaker 1 share %d%% · ".format(
                    result.similarity, (result.shareOf1 * 100).toInt()) +
                "me=${me ?: "?"}${if (owner == null) " (no voice sample)" else
                    result.centroids.entries.joinToString("") { " (${it.key}: %.2f)".format(Voice.cosine(owner, it.value)) }
                } · ${lines.size} lines")
            Speakers(lines, result.centroids, me)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log("call ${call.id}: speaker separation FAILED, keeping plain transcript: ${describe(e)}")
            null
        }
    }

    private companion object {
        /** Level gap above which a call was certainly held to the ear (louder = owner). */
        const val HANDSET_GAP_DB = 15.0
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
