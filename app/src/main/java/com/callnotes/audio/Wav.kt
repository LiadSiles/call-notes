package com.callnotes.audio

import java.io.File
import java.io.RandomAccessFile

/** Minimal PCM16 WAV helpers. The pipeline's working format is 16 kHz mono 16-bit. */
object Wav {
    const val HEADER_BYTES = 44
    const val SAMPLE_RATE = 16_000

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataBytes: Long,
    ) {
        val isWorkingFormat get() = sampleRate == SAMPLE_RATE && channels == 1 && bitsPerSample == 16
        val sampleCount get() = dataBytes / (channels * bitsPerSample / 8)
        val durationMs get() = sampleCount * 1000 / sampleRate
    }

    fun header(dataBytes: Long, sampleRate: Int = SAMPLE_RATE, channels: Int = 1): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val h = ByteArray(HEADER_BYTES)
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

    /** Parses RIFF chunks; null if not a PCM WAV. */
    fun readInfo(file: File): Info? = try {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(12)
            f.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(head, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) return null
            var rate = 0; var channels = 0; var bits = 0; var format = 0
            while (f.filePointer + 8 <= f.length()) {
                val id = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
                val size = readLe32(f)
                when (id) {
                    "fmt " -> {
                        format = readLe16(f); channels = readLe16(f); rate = readLe32(f).toInt()
                        f.skipBytes(6); bits = readLe16(f)
                        f.seek(f.filePointer + size - 16)
                    }
                    "data" -> {
                        if (format != 1) return null
                        // Recorders that crashed leave size 0 / garbage: use what's on disk.
                        val onDisk = f.length() - f.filePointer
                        val bytes = if (size in 1L..onDisk) size else onDisk
                        return Info(rate, channels, bits, f.filePointer, bytes)
                    }
                    else -> f.seek(f.filePointer + size + (size and 1L))
                }
            }
            null
        }
    } catch (e: Exception) {
        null
    }

    /** Reads [count] mono samples starting at [start] (working format only). */
    fun readSamples(file: File, info: Info, start: Long, count: Int): ShortArray {
        val n = count.coerceAtMost((info.sampleCount - start).coerceAtLeast(0).toInt())
        val bytes = ByteArray(n * 2)
        RandomAccessFile(file, "r").use { f ->
            f.seek(info.dataOffset + start * 2)
            f.readFully(bytes)
        }
        return ShortArray(n) { i ->
            ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
    }

    fun write(file: File, samples: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        val data = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            data[2 * i] = (s and 0xFF).toByte()
            data[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        file.outputStream().use { out ->
            out.write(header(data.size.toLong(), sampleRate))
            out.write(data)
        }
    }

    private fun readLe32(f: RandomAccessFile): Long {
        var v = 0L
        for (i in 0..3) v = v or ((f.read().toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun readLe16(f: RandomAccessFile): Int = (f.read() and 0xFF) or ((f.read() and 0xFF) shl 8)
}
