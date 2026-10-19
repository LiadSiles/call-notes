package com.callnotes.ui

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Plays one recording at a time; [playingId] drives the play/stop buttons. */
class AudioPlayer {

    var playingId by mutableStateOf<Long?>(null)
        private set

    private var player: MediaPlayer? = null

    fun toggle(id: Long, path: String) {
        val wasPlaying = playingId == id
        stop()
        if (wasPlaying) return
        player = try {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { this@AudioPlayer.stop() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            null
        }
        playingId = if (player != null) id else null
    }

    fun stop() {
        player?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        player = null
        playingId = null
    }
}
