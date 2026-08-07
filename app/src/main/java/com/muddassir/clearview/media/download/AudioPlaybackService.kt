package com.muddassir.clearview.media.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R
import java.io.File

/**
 * Foreground media service that owns the offline-audio [MediaPlayer], so the
 * audio keeps playing when the app is backgrounded or the screen is off — with
 * a media notification (play / pause / stop, plus lock-screen and quick-settings
 * controls through [MediaSessionCompat]).
 *
 * [OfflineAudioPlayer] is the app-facing facade: every call forwards here via
 * intents, and the service writes the observable state back through
 * [OfflineAudioPlayer]'s Compose state, so the player screen, the Downloads
 * list and the notification all stay in sync no matter where playback is
 * controlled from.
 */
class AudioPlaybackService : Service() {

    companion object {
        private const val TAG = "AudioPlaybackService"
        const val CHANNEL_ID = "audio_playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.muddassir.clearview.action.PLAY"
        const val ACTION_TOGGLE = "com.muddassir.clearview.action.TOGGLE"
        const val ACTION_PAUSE = "com.muddassir.clearview.action.PAUSE"
        const val ACTION_RESUME = "com.muddassir.clearview.action.RESUME"
        const val ACTION_SEEK = "com.muddassir.clearview.action.SEEK"
        const val ACTION_SET_SPEED = "com.muddassir.clearview.action.SET_SPEED"
        const val ACTION_STOP = "com.muddassir.clearview.action.STOP"

        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_THUMB = "thumb_path"
        const val EXTRA_SEEK_MS = "seek_ms"
        const val EXTRA_SPEED = "speed"

        /** Playback-speed bounds (PlaybackParams accepts 0.5x–2.0x). */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f

        /** Starts background playback of the downloaded audio [item]. */
        fun play(context: Context, item: DownloadItem) {
            val file = File(context.cacheDir, "audio/${item.fileName}")
            if (!file.exists()) return
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_VIDEO_ID, item.videoId)
                putExtra(EXTRA_FILE_PATH, file.absolutePath)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_CHANNEL, item.channelName.ifBlank { "YouTube" })
                putExtra(EXTRA_THUMB, item.thumbnailPath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Sends a lightweight command (pause/resume/seek/stop/speed) to the service. */
        fun send(context: Context, action: String, seekMs: Long = 0L, speed: Float = 1f) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                this.action = action
                if (seekMs > 0L) putExtra(EXTRA_SEEK_MS, seekMs)
                if (action == ACTION_SET_SPEED) putExtra(EXTRA_SPEED, speed)
            }
            context.startService(intent)
        }
    }

    private var player: MediaPlayer? = null
    private var session: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var currentTitle = "ClearView audio"
    private var currentChannel = "Offline audio"
    private var currentThumb: Bitmap? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 1 s position ticker: keeps Compose state, the media session and the
     *  notification progress in sync while playing. */
    private val ticker = object : Runnable {
        override fun run() {
            val mp = player ?: return
            val pos = try { mp.currentPosition.toLong().coerceAtLeast(0L) } catch (e: Exception) { 0L }
            OfflineAudioPlayer.positionMs.longValue = pos
            setPlaybackState(OfflineAudioPlayer.isPlaying.value, pos)
            if (OfflineAudioPlayer.isPlaying.value) notifyProgress(pos)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "ClearViewAudio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onStop() { stopPlayback() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                // startForegroundService grants ~5 s to post the foreground
                // notification — do it immediately, then set up the player.
                startForegroundCompat(buildNotification(playing = false))
                handlePlay(intent)
            }
            ACTION_TOGGLE -> {
                if (player == null) {
                    stopSelf()
                } else if (OfflineAudioPlayer.isPlaying.value) {
                    pause()
                } else {
                    resume()
                }
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_SEEK -> seekTo(intent.getLongExtra(EXTRA_SEEK_MS, 0L))
            ACTION_SET_SPEED -> {
                // A speed change with no loaded player is a no-op — stop the
                // service again so a stray command can't leave it idling.
                if (player != null) {
                    applySpeed(intent.getFloatExtra(EXTRA_SPEED, 1f))
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTicker()
        runCatching { player?.stop() }
        player?.release()
        player = null
        session?.release()
        session = null
        super.onDestroy()
    }

    // ── Playback ───────────────────────────────────────────────────

    private fun handlePlay(intent: Intent) {
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "ClearView audio"
        currentChannel = intent.getStringExtra(EXTRA_CHANNEL) ?: "Offline audio"

        stopInternal()
        currentThumb = null

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(filePath)
            mp.setOnPreparedListener { prepared ->
                OfflineAudioPlayer.durationMs.longValue = prepared.duration.toLong().coerceAtLeast(0L)
                OfflineAudioPlayer.positionMs.longValue = 0L
                // Apply the persisted playback speed before playback starts (a
                // few OEMs only accept PlaybackParams once playing — the
                // fallback below retries right after start()).
                var speedApplied = false
                runCatching {
                    prepared.playbackParams = prepared.playbackParams.setSpeed(
                        OfflineAudioPlayer.speed.value.coerceIn(MIN_SPEED, MAX_SPEED)
                    )
                    speedApplied = true
                }
                prepared.start()
                OfflineAudioPlayer.isPlaying.value = true
                requestAudioFocus()
                startTicker()
                setPlaybackState(playing = true, positionMs = 0L)
                updateNotification(playing = true)
                if (!speedApplied && OfflineAudioPlayer.speed.value != 1f) {
                    runCatching {
                        prepared.playbackParams = prepared.playbackParams.setSpeed(
                            OfflineAudioPlayer.speed.value.coerceIn(MIN_SPEED, MAX_SPEED)
                        )
                    }
                }
            }
            mp.setOnCompletionListener { finishPlayback() }
            mp.setOnErrorListener { _, _, _ ->
                Log.w(TAG, "PLAYER_ERROR")
                stopPlayback()
                true
            }
            mp.prepareAsync()
            player = mp
            OfflineAudioPlayer.playingVideoId.value = videoId
        } catch (e: Exception) {
            Log.w(TAG, "SETUP_FAILED ${e.message}")
            runCatching { mp.release() }
            player = null
            OfflineAudioPlayer.playingVideoId.value = null
            stopSelf()
            return
        }
        // Notification large icon: the local thumbnail, decoded off the main
        // thread so playback setup is never blocked by disk IO.
        val thumbPath = intent.getStringExtra(EXTRA_THUMB) ?: ""
        if (thumbPath.isNotBlank()) {
            Thread {
                val bmp = loadThumb(thumbPath)
                if (bmp != null) {
                    currentThumb = bmp
                    handler.post { updateNotification(playing = OfflineAudioPlayer.isPlaying.value) }
                }
            }.start()
        }
        updateNotification(playing = false)
    }

    private fun pause() {
        player?.pause()
        OfflineAudioPlayer.isPlaying.value = false
        stopTicker()
        setPlaybackState(playing = false, positionMs = OfflineAudioPlayer.positionMs.longValue)
        updateNotification(playing = false)
    }

    private fun resume() {
        val mp = player
        if (mp != null && OfflineAudioPlayer.playingVideoId.value != null) {
            mp.start()
            OfflineAudioPlayer.isPlaying.value = true
            startTicker()
            setPlaybackState(playing = true, positionMs = OfflineAudioPlayer.positionMs.longValue)
            updateNotification(playing = true)
        }
    }

    private fun seekTo(ms: Long) {
        val pos = ms.coerceAtLeast(0L)
        player?.seekTo(pos.toInt())
        OfflineAudioPlayer.positionMs.longValue = pos
        setPlaybackState(OfflineAudioPlayer.isPlaying.value, pos)
    }

    /** Applies a new playback speed to the live player (PlaybackParams, API 23+). */
    private fun applySpeed(rate: Float) {
        val mp = player ?: return
        try {
            mp.playbackParams = mp.playbackParams.setSpeed(rate.coerceIn(MIN_SPEED, MAX_SPEED))
        } catch (e: Exception) {
            Log.w(TAG, "SET_SPEED_FAILED ${e.message}")
        }
    }

    private fun finishPlayback() {
        val id = OfflineAudioPlayer.playingVideoId.value
        stopTicker()
        // Keep the track loaded, paused at 0, so the notification / player
        // screen Play button replays it — a dead end-of-track button is worse
        // UX than leaving the track loaded.
        runCatching { player?.seekTo(0) }
        OfflineAudioPlayer.isPlaying.value = false
        OfflineAudioPlayer.positionMs.longValue = 0L
        setPlaybackState(playing = false, positionMs = 0L)
        updateNotification(playing = false)
        if (id != null) AudioDownloads.markPlayed(id)
    }

    private fun stopPlayback() {
        val wasLoaded = player != null || OfflineAudioPlayer.playingVideoId.value != null
        stopInternal()
        OfflineAudioPlayer.playingVideoId.value = null
        OfflineAudioPlayer.durationMs.longValue = 0L
        OfflineAudioPlayer.isPlaying.value = false
        OfflineAudioPlayer.positionMs.longValue = 0L
        if (wasLoaded) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun stopInternal() {
        stopTicker()
        runCatching { player?.stop() }
        player?.release()
        player = null
        abandonAudioFocus()
    }

    private fun startTicker() {
        stopTicker()
        handler.post(ticker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    // ── Notification ───────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.audio_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.audio_notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(playing: Boolean): android.app.Notification {
        val playPauseAction = if (playing) {
            NotificationCompat.Action(
                R.drawable.ic_media_pause,
                getString(R.string.audio_notification_pause),
                pendingService(ACTION_PAUSE, 0)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_media_play,
                getString(R.string.audio_notification_play),
                pendingService(ACTION_RESUME, 1)
            )
        }
        val stopAction = NotificationCompat.Action(
            R.drawable.ic_audio_notification,
            getString(R.string.audio_notification_stop),
            pendingService(ACTION_STOP, 2)
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val durationSec = (OfflineAudioPlayer.durationMs.longValue / 1000L).toInt().coerceAtLeast(0)
        val posSec = (OfflineAudioPlayer.positionMs.longValue / 1000L).toInt().coerceAtLeast(0)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clearview)
            .setContentTitle(currentTitle)
            .setContentText(currentChannel)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setProgress(durationSec, posSec, durationSec <= 0)
            .setLargeIcon(currentThumb)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .addAction(playPauseAction)
            .addAction(stopAction)
            .build()
    }

    private fun pendingService(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AudioPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun updateNotification(playing: Boolean) {
        val notification = buildNotification(playing)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+): the foreground
            // service still runs; there is just no notification to update.
        }
    }

    /** Progress-only refresh (throttled to the 1 s ticker, so never rebuilt
     *  faster than that). */
    private fun notifyProgress(posMs: Long) {
        val durationSec = (OfflineAudioPlayer.durationMs.longValue / 1000L).toInt().coerceAtLeast(0)
        if (durationSec <= 0) return
        val notification = buildNotification(playing = true)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // no-op (see updateNotification)
        }
    }

    private fun loadThumb(thumbPath: String): Bitmap? {
        return try {
            val f = File(cacheDir, "thumbnails/$thumbPath")
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (e: Exception) {
            null
        }
    }

    // ── Media session state (lock screen / system media UI) ────────

    private fun setPlaybackState(playing: Boolean, positionMs: Long) {
        val state = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                // Report the live speed so lock-screen / system media UI shows
                // the actual rate while playing.
                .setState(state, positionMs, OfflineAudioPlayer.speed.value)
                .build()
        )
    }

    // ── Audio focus (polite media playback) ────────────────────────

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            else -> Unit
        }
    }

    private fun requestAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }

    private fun abandonAudioFocus() {
        audioManager?.abandonAudioFocus(focusListener)
        audioManager = null
    }
}
