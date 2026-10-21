package com.callnotes.inference.speakers

import android.content.Context
import java.io.File

/**
 * The owner's voice fingerprint, from a one-time sample recorded in settings. Stored only
 * on the phone (`files/voice_profile.bin`); used to tell "me" from the other side.
 */
object VoiceProfile {

    private fun file(context: Context) = File(context.filesDir, "voice_profile.bin")

    fun load(context: Context): FloatArray? =
        runCatching { Voice.unpack(file(context).readBytes())[0] }.getOrNull()

    fun save(context: Context, embedding: FloatArray) {
        file(context).writeBytes(Voice.pack(mapOf(0 to embedding))!!)
    }

    fun delete(context: Context) { file(context).delete() }

    fun exists(context: Context) = file(context).exists()

    /**
     * Re-decides "me" in every call that has stored voices — after a new voice sample, or
     * with none (all back to unknown). Cheap: compares stored fingerprints only.
     */
    suspend fun relabelAll(context: Context): Int {
        val dao = (context.applicationContext as com.callnotes.CallNotesApp).database.calls()
        val owner = load(context)
        var matched = 0
        for (v in dao.allSpeakerEmbeddings()) {
            val me = owner?.let { Voice.whichIsMe(it, Voice.unpack(v.speakerEmbeddings)) }
            if (me != null) matched++
            dao.setMeSpeaker(v.id, me)
        }
        return matched
    }
}
