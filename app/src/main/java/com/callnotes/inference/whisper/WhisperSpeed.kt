package com.callnotes.inference.whisper

import com.callnotes.util.Prefs
import java.io.File

/**
 * Speed/accuracy presets for Whisper. The faster ones use a smaller copy of the downloaded
 * model, made on the phone itself by quantizing its weights (no server involved), and
 * optionally a single decoding path instead of five.
 *
 * | preset   | weights           | decoding  | file      |
 * |----------|-------------------|-----------|-----------|
 * | ACCURATE | f16 (as released) | beam 5    | ~1.6 GB   |
 * | BALANCED | q8_0 (8-bit)      | beam 5    | ~0.87 GB  |
 * | FAST     | q5_0 (5-bit)      | greedy    | ~0.57 GB  |
 */
enum class WhisperSpeed(val quantType: Int?, val beamSize: Int, val suffix: String?, val label: String, val note: String) {
    ACCURATE(null, 5, null, "מדויק", "המודל המלא, חמישה מסלולי פענוח. האיטי והמדויק ביותר."),
    BALANCED(8, 5, "q8_0", "מאוזן", "עותק 8-ביט של המודל (נוצר בטלפון). איבוד דיוק זניח בדרך כלל, מהיר יותר וחוסך זיכרון."),
    FAST(5, 1, "q5_0", "מהיר", "עותק 5-ביט ומסלול פענוח יחיד. המהיר ביותר; ייתכנו יותר טעויות במילים נדירות.");

    /** The model file this preset runs on, next to the downloaded one. */
    fun fileFor(base: File): File =
        if (suffix == null) base else File(base.parentFile, "${base.nameWithoutExtension}.$suffix.bin")

    /** Rough size of the derived file, for the space check and the UI. */
    fun expectedBytes(base: File): Long = when (this) {
        ACCURATE -> base.length()
        BALANCED -> (base.length() * 0.54).toLong()
        FAST -> (base.length() * 0.35).toLong()
    }

    companion object {
        fun current(prefs: Prefs): WhisperSpeed =
            entries.firstOrNull { it.name == prefs.transcribeSpeed } ?: ACCURATE

        /** Removes the derived copies (when the base model is deleted, or to free space). */
        fun deleteDerived(base: File) {
            entries.filter { it.suffix != null }.forEach {
                it.fileFor(base).delete()
                File(it.fileFor(base).path + ".part").delete()
            }
        }

        /**
         * Makes [speed]'s copy of [base] if it isn't there yet. Blocking (a minute or two on
         * a phone); [abort] stops it. Returns null on success, else the reason.
         */
        fun prepare(base: File, speed: WhisperSpeed, onProgress: (Int) -> Unit): String? {
            val type = speed.quantType ?: return null
            val out = speed.fileFor(base)
            if (out.isFile && out.length() > 0) return null
            val part = File(out.path + ".part")
            if (base.parentFile!!.usableSpace < speed.expectedBytes(base) + 300L * 1024 * 1024) return "אין מספיק מקום פנוי"
            WhisperNative.load()
            val err = WhisperNative.quantize(base.absolutePath, part.absolutePath, type) { onProgress(it) }
            if (err != null) { part.delete(); return err }
            if (!part.renameTo(out)) { part.delete(); return "rename failed" }
            return null
        }

        fun abort() = runCatching { WhisperNative.quantizeAbort() }
    }
}
