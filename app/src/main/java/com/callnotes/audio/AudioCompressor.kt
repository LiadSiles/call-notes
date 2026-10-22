package com.callnotes.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Re-encodes a 16 kHz mono WAV recording as AAC in an .m4a file, about 8× smaller
 * (~22 MB an hour instead of ~115 MB) with no audible difference for speech at 48 kbps.
 * Used after a call is transcribed, if the user turned compression on. The WAV is removed
 * only after the new file is checked (same length).
 */
object AudioCompressor {

    const val BITRATE = 48_000
    private const val TIMEOUT_US = 10_000L

    /** Returns the .m4a file, or null if anything went wrong (the WAV is then kept). */
    fun compress(wav: File, out: File): File? {
        val info = Wav.readInfo(wav) ?: return null
        if (!info.isWorkingFormat || info.sampleCount == 0L) return null
        val part = File(out.path + ".part")
        val ok = runCatching { encode(wav, info, part) }.getOrElse { false }
        if (!ok || !sameLength(part, info.durationMs)) { part.delete(); return null }
        if (!part.renameTo(out)) { part.delete(); return null }
        return out
    }

    private fun encode(wav: File, info: Wav.Info, out: File): Boolean {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, info.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            RandomAccessFile(wav, "r").use { f ->
                f.seek(info.dataOffset)
                var remaining = info.dataBytes
                var samplesIn = 0L
                var inputDone = false
                val bufInfo = MediaCodec.BufferInfo()
                val chunk = ByteArray(8_192)
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = minOf(remaining, minOf(buf.capacity(), chunk.size).toLong()).toInt() and 1.inv()
                            val pts = samplesIn * 1_000_000L / info.sampleRate
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                f.readFully(chunk, 0, n)
                                buf.clear(); buf.put(chunk, 0, n)
                                codec.queueInputBuffer(i, 0, n, pts, 0)
                                remaining -= n
                                samplesIn += n / 2
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!
                            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufInfo.size = 0
                            if (bufInfo.size > 0 && muxing) {
                                buf.position(bufInfo.offset); buf.limit(bufInfo.offset + bufInfo.size)
                                muxer.writeSampleData(track, buf, bufInfo)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
            return muxing
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (muxing) muxer.stop() }; runCatching { muxer.release() }
        }
    }

    /** The encoded file plays for as long as the original (within a second). */
    private fun sameLength(file: File, expectedMs: Long): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.path)
            val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull {
                it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val ms = f.getLong(MediaFormat.KEY_DURATION) / 1000
            abs(ms - expectedMs) <= 1_000
        } catch (_: Exception) {
            false
        } finally {
            ex.release()
        }
    }
}
