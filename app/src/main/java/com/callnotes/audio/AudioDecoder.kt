package com.callnotes.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * Converts any audio file Android can decode (WAV, AMR, M4A/AAC, MP3, OGG/Opus…) into
 * the pipeline's working format: 16 kHz mono 16-bit WAV. Streams, so long files don't
 * need to fit in memory. Down-mixes by averaging channels; resamples linearly (enough
 * for speech recognition).
 */
object AudioDecoder {

    /** Returns [input] if it is already in the working format, else decodes into [out]. */
    fun ensureWorkingFormat(input: File, out: File): File {
        val info = Wav.readInfo(input)
        if (info != null && info.isWorkingFormat) return input
        decode(input, out)
        return out
    }

    fun decode(input: File, out: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); throw IllegalArgumentException("No audio track in ${input.name}") }
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        val resampler = Resampler()
        var written = 0L

        RandomAccessFile(out, "rw").use { file ->
            file.setLength(0)
            file.write(ByteArray(Wav.HEADER_BYTES))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            val mono = if (isFloat) {
                                val fb = buf.asFloatBuffer()
                                val frames = fb.remaining() / channels
                                FloatArray(frames) { i ->
                                    var sum = 0f
                                    for (c in 0 until channels) sum += fb.get(i * channels + c)
                                    sum / channels * 32767f
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                val frames = sb.remaining() / channels
                                FloatArray(frames) { i ->
                                    var sum = 0f
                                    for (c in 0 until channels) sum += sb.get(i * channels + c)
                                    sum / channels
                                }
                            }
                            val resampled = resampler.process(mono, rate)
                            val bytes = ByteArray(resampled.size * 2)
                            for (i in resampled.indices) {
                                val s = resampled[i].toInt().coerceIn(-32768, 32767)
                                bytes[2 * i] = (s and 0xFF).toByte()
                                bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                            }
                            file.write(bytes)
                            written += bytes.size
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            file.seek(0)
            file.write(Wav.header(written))
        }
        codec.stop(); codec.release(); extractor.release()
    }

    /** Streaming linear resampler to 16 kHz that carries its phase across buffers. */
    private class Resampler {
        private var pos = 0.0          // next output position, in input samples, relative to buffer start
        private var prev = 0f          // last sample of the previous buffer (index -1)

        fun process(x: FloatArray, inRate: Int): FloatArray {
            if (x.isEmpty()) return FloatArray(0)
            if (inRate == Wav.SAMPLE_RATE) { prev = x.last(); return x }
            val step = inRate.toDouble() / Wav.SAMPLE_RATE
            val out = ArrayList<Float>((x.size / step).toInt() + 2)
            while (pos < x.size - 1) {
                val i = kotlin.math.floor(pos).toInt()
                val frac = (pos - i).toFloat()
                val a = if (i < 0) prev else x[i]
                val b = x[i + 1]
                out.add(a + (b - a) * frac)
                pos += step
            }
            pos -= x.size
            prev = x.last()
            return out.toFloatArray()
        }
    }
}
