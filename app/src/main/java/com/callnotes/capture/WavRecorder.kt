package com.callnotes.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executor

/**
 * Records 16 kHz mono 16-bit PCM straight into a WAV file — the format the
 * transcription models take, so no decoding step is needed later.
 * (~1.9 MB per minute; compression comes later.)
 */
class WavRecorder(
    private val file: File,
    private val callbackExecutor: Executor,
    private val trace: CallTrace? = null,
    private val sampleRate: Int = 16_000,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    /** Highest absolute sample seen. Near zero for the whole call = we got silence. */
    @Volatile var peakLevel: Int = 0
        private set

    /** The system reported it is feeding this recording silence. */
    @Volatile var silenced: Boolean = false
        private set

    var sourceName: String? = null
        private set

    private var lastSilenced = false
    private var lastDevice: Int? = null

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val session = record?.audioSessionId ?: return
            val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
            if (mine.isClientSilenced) silenced = true
            if (mine.isClientSilenced != lastSilenced) {
                lastSilenced = mine.isClientSilenced
                trace?.event(if (mine.isClientSilenced) "capture SILENCED by system policy" else "capture un-silenced")
            }
            val device = mine.audioDevice?.type
            if (device != lastDevice) {
                lastDevice = device
                trace?.event("input device type=$device")
            }
            // Other apps capturing at the same time (e.g. the call itself, the dialer).
            trace?.event("recording configs: ${configs.size} active, ours silenced=${mine.isClientSilenced}")
        }
    }

    /** Caller must hold RECORD_AUDIO. Returns false if no audio source could start. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false
        val bufferBytes = maxOf(minBuffer, sampleRate) // >= 0.5 s of audio

        // VOICE_RECOGNITION skips most of the device's voice processing (AGC, noise
        // suppression), which keeps the far side — coming out of the speaker — louder.
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "voice_recognition",
            MediaRecorder.AudioSource.MIC to "mic",
        )
        for ((source, name) in sources) {
            val candidate = try {
                AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            } catch (e: Exception) {
                null
            } ?: continue

            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                candidate.release(); continue
            }
            candidate.registerAudioRecordingCallback(callbackExecutor, recordingCallback)
            val started = try {
                candidate.startRecording()
                candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING
            } catch (e: Exception) {
                false
            }
            if (!started) {
                candidate.unregisterAudioRecordingCallback(recordingCallback)
                candidate.release(); continue
            }
            record = candidate
            sourceName = name
            trace?.event("recorder started: source=$name buffer=${bufferBytes}B")
            break
        }

        val active = record ?: return false
        running = true
        thread = Thread({ writeLoop(active, bufferBytes / 2) }, "wav-recorder").apply { start() }
        return true
    }

    /** Signals the recording to stop. Non-blocking; call [awaitFinished] off the main thread. */
    fun stop() {
        running = false
        try {
            record?.stop() // unblocks a pending read()
        } catch (_: IllegalStateException) {
        }
    }

    /** Waits for the file to be finalized and releases the recorder. */
    fun awaitFinished() {
        thread?.join(5_000)
        thread = null
        record?.let {
            it.unregisterAudioRecordingCallback(recordingCallback)
            it.release()
        }
        record = null
    }

    private fun writeLoop(rec: AudioRecord, samplesPerRead: Int) {
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write(ByteArray(WAV_HEADER_BYTES)) // placeholder, filled in at the end

            val samples = ShortArray(samplesPerRead)
            val bytes = ByteArray(samplesPerRead * 2)
            var dataBytes = 0L
            var peak = 0
            // Per-second stats for the trace.
            var secSumSq = 0.0
            var secZeros = 0
            var secCount = 0
            var lastRead = System.nanoTime()

            while (running) {
                val n = rec.read(samples, 0, samples.size)
                val now = System.nanoTime()
                val gapMs = (now - lastRead) / 1_000_000
                lastRead = now
                // A read covers ≤ ~0.25 s of audio; much longer means the thread was starved.
                if (gapMs > 800) trace?.event("read stalled ${gapMs}ms")
                if (n < 0) { trace?.event("read error $n — recording stopped"); break }
                if (n == 0) continue
                for (i in 0 until n) {
                    val s = samples[i].toInt()
                    val abs = if (s < 0) -s else s
                    if (abs > peak) peak = abs
                    bytes[2 * i] = (s and 0xFF).toByte()
                    bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                    secSumSq += s.toDouble() * s
                    if (s == 0) secZeros++
                    secCount++
                    if (secCount == sampleRate) {
                        trace?.second(kotlin.math.sqrt(secSumSq / secCount), secZeros.toDouble() / secCount)
                        secSumSq = 0.0; secZeros = 0; secCount = 0
                    }
                }
                peakLevel = peak
                out.write(bytes, 0, n * 2)
                dataBytes += n * 2
            }

            out.seek(0)
            out.write(wavHeader(dataBytes, sampleRate))
        }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44

        fun wavHeader(dataBytes: Long, sampleRate: Int): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val h = ByteArray(WAV_HEADER_BYTES)
            fun str(at: Int, s: String) = s.forEachIndexed { i, c -> h[at + i] = c.code.toByte() }
            fun int32(at: Int, v: Long) { for (i in 0..3) h[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
            fun int16(at: Int, v: Int) { h[at] = (v and 0xFF).toByte(); h[at + 1] = ((v shr 8) and 0xFF).toByte() }

            str(0, "RIFF"); int32(4, 36 + dataBytes); str(8, "WAVE")
            str(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, channels)
            int32(24, sampleRate.toLong()); int32(28, byteRate.toLong())
            int16(32, channels * bitsPerSample / 8); int16(34, bitsPerSample)
            str(36, "data"); int32(40, dataBytes)
            return h
        }
    }
}
