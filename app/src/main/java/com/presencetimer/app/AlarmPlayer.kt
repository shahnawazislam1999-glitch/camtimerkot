package com.presencetimer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

/** Loops the bundled alarm.mp3 (res/raw) on the ALARM stream. Works in the background. */
class AlarmPlayer(private val context: Context) {
    private var player: MediaPlayer? = null

    val isPlaying: Boolean get() = try { player?.isPlaying == true } catch (_: Exception) { false }

    fun start() {
        if (isPlaying) return
        stop()
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val mp = MediaPlayer.create(context, R.raw.alarm, attrs, AudioManager.AUDIO_SESSION_ID_GENERATE) ?: return
            mp.isLooping = true
            mp.setVolume(1f, 1f)
            mp.start()
            player = mp
        } catch (_: Exception) { }
    }

    fun stop() {
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        player = null
    }
}
