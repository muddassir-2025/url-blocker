package com.muddassir.clearview.media.download

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * A single, lightweight [MediaPlayer]-based controller for the offline audio
 * player (podcast-style). Only one audio plays at a time; state is Compose
 * state so the player screen and the rest of the app observe it directly.
 */
object OfflineAudioPlayer {

    /** videoId of the loaded audio, or null when nothing is loaded. */
    val playingVideoId = mutableStateOf<String?>(null)
    val isPlaying = mutableStateOf(false)
    val positionMs = mutableLongStateOf(0L)
    val durationMs = mutableLongStateOf(0L)

    private var player: MediaPlayer? = null
    private var tickerJob: Job? = null
    private var audioManager: AudioManager? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Loads and starts playing [file] (the downloaded audio for [videoId]). */
    fun play(context: Context, file: File, videoId: String) {
        stopInternal()
        playingVideoId.value = videoId
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { prepared ->
                durationMs.longValue = prepared.duration.toLong().coerceAtLeast(0L)
                positionMs.longValue = 0L
                prepared.start()
                isPlaying.value = true
                requestAudioFocus(context)
                startTicker()
            }
            mp.setOnCompletionListener { finishPlayback() }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            mp.release()
            player = null
            playingVideoId.value = null
        }
    }

    fun toggle() {
        if (isPlaying.value) pause() else resume()
    }

    fun pause() {
        player?.pause()
        isPlaying.value = false
        stopTicker()
    }

    fun resume() {
        val mp = player
        if (mp != null && playingVideoId.value != null) {
            mp.start()
            isPlaying.value = true
            startTicker()
        }
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.toInt().coerceAtLeast(0))
        positionMs.longValue = ms.coerceAtLeast(0L)
    }

    /** Stops playback, records the play, and clears the loaded audio. */
    fun stop() {
        val id = playingVideoId.value
        stopInternal()
        playingVideoId.value = null
        durationMs.longValue = 0L
        if (id != null) AudioDownloads.markPlayed(id)
    }

    private fun finishPlayback() {
        val id = playingVideoId.value
        stopInternal()
        playingVideoId.value = null
        durationMs.longValue = 0L
        if (id != null) AudioDownloads.markPlayed(id)
    }

    private fun stopInternal() {
        stopTicker()
        runCatching { player?.stop() }
        player?.release()
        player = null
        isPlaying.value = false
        positionMs.longValue = 0L
        abandonAudioFocus()
    }

    private fun startTicker() {
        stopTicker()
        tickerJob = scope.launch {
            while (isActive) {
                positionMs.longValue = player?.currentPosition?.toLong() ?: 0L
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    // ── Audio focus (polite media playback) ────────────────────────

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            else -> Unit
        }
    }

    private fun requestAudioFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }

    private fun abandonAudioFocus() {
        audioManager?.abandonAudioFocus(focusListener)
        audioManager = null
    }
}
