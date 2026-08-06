package com.muddassir.clearview.media.download

import android.content.Context
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * App-facing facade for offline-audio playback.
 *
 * The actual [android.media.MediaPlayer] lives inside [AudioPlaybackService] —
 * a foreground media service — so the audio keeps playing when the app is
 * backgrounded or the screen is off, with a media notification (play/pause/
 * stop, lock-screen controls) attached. All state stays Compose state, so the
 * player screen and the rest of the app observe it exactly as before; every
 * command is forwarded to the service through intents.
 */
object OfflineAudioPlayer {

    /** videoId of the loaded audio, or null when nothing is loaded. */
    val playingVideoId = mutableStateOf<String?>(null)
    val isPlaying = mutableStateOf(false)
    val positionMs = mutableLongStateOf(0L)
    val durationMs = mutableLongStateOf(0L)
    /** Current playback speed (1.0 = normal), persisted across sessions. */
    val speed = mutableStateOf(1f)

    private var appContext: Context? = null

    /**
     * Loads and starts background playback of the downloaded [item].
     *
     * No-op when [item] is already the loaded audio: with playback continuing
     * in the background after the player screen closes, re-opening the screen
     * must NOT restart the track — it simply shows the ongoing playback.
     */
    fun play(context: Context, item: DownloadItem) {
        appContext = context.applicationContext
        // Sync the persisted speed into Compose state so the player screen and
        // the notification reflect the saved rate even after a restart (the
        // service applies it when the new player is prepared).
        speed.value = persistedSpeed(context)
        if (playingVideoId.value == item.videoId) return
        AudioPlaybackService.play(context, item)
    }

    fun toggle() {
        val ctx = appContext ?: return
        if (playingVideoId.value == null) return
        if (isPlaying.value) {
            AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_PAUSE)
        } else {
            AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_RESUME)
        }
    }

    fun pause() {
        val ctx = appContext ?: return
        if (playingVideoId.value == null) return
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_PAUSE)
    }

    fun resume() {
        val ctx = appContext ?: return
        if (playingVideoId.value == null) return
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_RESUME)
    }

    fun seekTo(ms: Long) {
        val ctx = appContext ?: return
        if (playingVideoId.value == null) return
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_SEEK, ms.coerceAtLeast(0L))
    }

    /** Stops playback; the service tears down its notification and itself. */
    fun stop() {
        val ctx = appContext ?: return
        if (playingVideoId.value == null) return
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_STOP)
    }

    /**
     * Changes the playback speed. Persisted so it survives restarts and is
     * applied to the live player by [AudioPlaybackService]; the Compose state
     * updates immediately so the player screen and notification follow.
     */
    fun setSpeed(context: Context, rate: Float) {
        val ctx = context.applicationContext
        appContext = ctx
        speed.value = rate
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SPEED, rate).apply()
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_SET_SPEED, speed = rate)
    }

    private fun persistedSpeed(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1f)

    private const val PREFS_NAME = "offline_audio_player"
    private const val KEY_SPEED = "playback_speed"
}
