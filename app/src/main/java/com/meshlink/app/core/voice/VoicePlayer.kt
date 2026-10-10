package com.meshlink.app.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File

/** Plays a specific completed voice asset. Only one asset plays at a time. */
class VoicePlayer {
    private var player: MediaPlayer? = null
    private var currentPath: String? = null
    var onPlaybackChanged: ((String?) -> Unit)? = null

    @Synchronized
    fun play(context: Context, path: String) {
        stop()
        val file = File(path)
        if (!file.isFile || file.length() == 0L) {
            Log.e(TAG, "Voice asset missing or empty: $path")
            return
        }
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnPreparedListener { prepared ->
                    if (player === prepared) {
                        prepared.setVolume(1f, 1f)
                        prepared.start()
                        onPlaybackChanged?.invoke(file.absolutePath)
                    }
                }
                setOnCompletionListener { finished -> releaseIfCurrent(finished) }
                setOnErrorListener { failed, what, extra ->
                    Log.e(TAG, "Playback error what=$what extra=$extra path=$path")
                    releaseIfCurrent(failed)
                    true
                }
            }
            player = mp
            currentPath = file.absolutePath
            // Use an explicit file URI so every tap resolves the selected asset,
            // rather than relying on a shared/last-recording reference.
            mp.setDataSource(context, Uri.fromFile(file))
            mp.prepareAsync()
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to play voice: $path", t)
            player?.runCatching { release() }
            player = null
            currentPath = null
        }
    }

    @Synchronized
    fun stop() {
        val old = player
        player = null
        currentPath = null
        try { old?.stop() } catch (_: Throwable) {}
        try { old?.release() } catch (_: Throwable) {}
        onPlaybackChanged?.invoke(null)
    }

    @Synchronized
    private fun releaseIfCurrent(mp: MediaPlayer) {
        if (player === mp) {
            player = null
            currentPath = null
            onPlaybackChanged?.invoke(null)
        }
        runCatching { mp.release() }
    }

    companion object { private const val TAG = "MeshLinkVoice" }
}
