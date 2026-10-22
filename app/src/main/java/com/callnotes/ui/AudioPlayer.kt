package com.callnotes.ui

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Plays one recording at a time. The loaded recording stays loaded while paused, so
 * the card keeps its seek bar; opening another recording releases the previous one.
 */
class AudioPlayer(private val appContext: android.content.Context) {

    /** The recording currently loaded (playing or paused), or null. */
    var loadedId by mutableStateOf<Long?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    private var player: MediaPlayer? = null

    /** Play/pause for [id]; loads it first if another recording (or none) is loaded. */
    fun toggle(id: Long, path: String) {
        if (loadedId == id && player != null) {
            val p = player!!
            if (p.isPlaying) p.pause() else p.start()
            isPlaying = p.isPlaying
            return
        }
        release()
        player = try {
            MediaPlayer().apply {
                // An encrypted recording is decrypted on the fly, never written out.
                if (com.callnotes.security.SealedFile.isSealed(path)) {
                    setDataSource(com.callnotes.security.SealedFile.Source(appContext, java.io.File(path)))
                } else {
                    setDataSource(path)
                }
                setOnCompletionListener {
                    this@AudioPlayer.isPlaying = false
                    this@AudioPlayer.positionMs = this@AudioPlayer.durationMs
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            null
        }
        val p = player ?: return
        loadedId = id
        durationMs = p.duration.coerceAtLeast(0)
        positionMs = 0
        isPlaying = true
    }

    /** Jumps to [ms] in recording [id], loading and playing it if needed (tap on a transcript line). */
    fun playFrom(id: Long, path: String, ms: Int) {
        if (loadedId != id || player == null) toggle(id, path)
        seekTo(ms)
        player?.let { if (!it.isPlaying) { it.start(); isPlaying = true } }
    }

    fun seekTo(ms: Int) {
        val p = player ?: return
        val target = ms.coerceIn(0, durationMs)
        p.seekTo(target)
        positionMs = target
    }

    /** Called periodically by the UI while playing. */
    fun refreshPosition() {
        val p = player ?: return
        if (p.isPlaying) positionMs = p.currentPosition
    }

    fun release() {
        player?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        player = null
        loadedId = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }
}
