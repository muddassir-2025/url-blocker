package com.muddassir.clearview.media.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.media.data.MediaLibraryStore
import com.muddassir.clearview.media.data.UserPlaylistStore
import com.muddassir.clearview.media.data.VideoProgress
import com.muddassir.clearview.media.data.WatchProgressStore
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadStatus
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.UserPlaylist
import com.muddassir.clearview.media.util.formatBytes
import com.muddassir.clearview.media.util.formatEtaRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * In-app video player built on the YouTube IFrame Player API.
 *
 * RENDERING: the WebView's video surface must never be covered by a sibling.
 * The player + its overlays live inside their OWN box that exactly matches the
 * video area (16:9 at the top in portrait, full screen in landscape); the
 * details panel sits BELOW that box in portrait, never overlapping it. The
 * player occupies the same composition slot in both orientations so it
 * survives rotation (playback continues).
 *
 * The UI reflects the player's REAL state from the IFrame API: a buffering
 * indicator while the video loads, the actual error card (by IFrame error
 * code) when playback fails, and a gentle hint when autoplay was blocked.
 *
 * Portrait shows a dedicated control panel BELOW the video (title, Continue
 * Watching / Watch Again, Share, Speed, Add to playlist, Hide, Mark as
 * watched, Download audio) — the video itself stays uncluttered. Vertical
 * fullscreen is a Shorts-style viewer: swipe up/down to navigate the
 * [shortsQueue] (when it has more than one item), exit via the on-screen
 * button or back.
 */
@Composable
fun VideoPlayerScreen(
    video: MediaVideo,
    isLandscape: Boolean,
    fullscreenVertical: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    /** Called after the user hides this video (the player should close). */
    onExit: () -> Unit = {},
    /** Plays the downloaded audio instead of the video (podcast-style). */
    onPlayOffline: () -> Unit = {},
    /** Ordered Shorts list for the vertical viewer (empty for long videos). */
    shortsQueue: List<MediaVideo> = emptyList(),
    /** Index of [video] within [shortsQueue], or -1. */
    shortsIndex: Int = -1,
    /** Swipe navigation: +1 = next Short, -1 = previous. */
    onNavigateShorts: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    // All player state is keyed on the current videoId so it resets
    // SYNCHRONOUSLY the moment the source changes (opening a video, swiping
    // through Shorts). The blurred loading placeholder is gated on this state:
    // a stale PLAYING/sourceStarted carried over from the previous video would
    // leave a 1-frame gap where the embed's own grey play button flashes while
    // the next video loads. Keying on the videoId closes that gap.
    var playerState by remember(video.videoId) { mutableStateOf(YtState.UNSTARTED) }
    var errorCode by remember(video.videoId) { mutableStateOf<Int?>(null) }
    var timedOut by remember(video.videoId) { mutableStateOf(false) }
    var retryToken by remember { mutableStateOf(0) }
    // Whether the current source has EVER started PLAYING (not merely become
    // ready): see onPlayerState — a ready-but-paused source (autoplay blocked)
    // keeps this false so the blurred placeholder stays up for Shorts. The
    // placeholder shows only until that first start — buffering caused by a
    // forward/backward seek must NOT re-blur the video.
    var sourceStarted by remember(video.videoId) { mutableStateOf(false) }

    // ── Blurred-loading backdrop (smooth video transitions) ─────────
    // While a NEW source loads (opening a video, swiping through Shorts), the
    // loading placeholder must never flash blank. It shows the blurred
    // thumbnail of the video we JUST LEFT — already in the in-memory cache,
    // so it renders instantly — until the new video's own thumbnail has
    // loaded, then crossfades to it. The thumbnails are tracked manually
    // (SideEffect runs after every recomposition): when [video] changes,
    // lastThumbnailUrl still holds the OLD video's thumbnail, so it is
    // promoted to previousThumbnailUrl before the new URL takes over.
    var lastThumbnailUrl by remember { mutableStateOf(video.thumbnailUrl) }
    var previousThumbnailUrl by remember { mutableStateOf<String?>(null) }
    // True once THIS video's thumbnail bitmap is ready (or already cached).
    // Reset on every video change so the previous thumbnail leads the way.
    var currentThumbnailReady by remember(video.videoId) { mutableStateOf(false) }
    SideEffect {
        if (lastThumbnailUrl != video.thumbnailUrl) {
            previousThumbnailUrl = lastThumbnailUrl
            lastThumbnailUrl = video.thumbnailUrl
        }
    }
    // The backdrop URL to show: the new thumbnail once it's loaded, otherwise
    // the previous video's (cached → instant, no blank frame).
    val placeholderThumbnail = if (currentThumbnailReady) video.thumbnailUrl
        else previousThumbnailUrl ?: video.thumbnailUrl
    // Prefetch the NEW video's thumbnail while the previous one is on screen:
    // the placeholder only ever DISPLAYS one URL at a time, so without this
    // warm-up the new thumbnail would never load and the backdrop could never
    // crossfade to it (it would show the previous video's blur until playback
    // starts). Warm the shared cache so the crossfade fires the moment the new
    // thumb is ready. A failed fetch just keeps the previous blurred backdrop.
    LaunchedEffect(video.videoId, video.thumbnailUrl) {
        val loaded = if (video.thumbnailUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                ThumbnailCache.get(video.thumbnailUrl) != null
            }
        } else {
            false
        }
        if (loaded) currentThumbnailReady = true
    }
    // Prefetch the NEIGHBORING Shorts' thumbnails while the current one is on
    // screen, so swiping to the next/previous short lands on an already-warm
    // cache: the blurred backdrop crossfades to that poster the instant the
    // source changes instead of waiting on a fresh network fetch. A failed
    // fetch is harmless — the previous video's blur stays up until playback
    // starts. Re-keys on every swipe (and on a queue swap, so the new pair is
    // always the one warmed).
    LaunchedEffect(video.videoId, shortsQueue) {
        if (shortsIndex !in shortsQueue.indices) return@LaunchedEffect
        val neighbors = buildList {
            if (shortsIndex + 1 in shortsQueue.indices) add(shortsQueue[shortsIndex + 1])
            if (shortsIndex - 1 >= 0) add(shortsQueue[shortsIndex - 1])
        }
        withContext(Dispatchers.IO) {
            neighbors.forEach { n ->
                if (n.thumbnailUrl.isNotBlank()) ThumbnailCache.get(n.thumbnailUrl)
            }
        }
    }
    // The "Open in YouTube app" option appears only after an in-app Retry has
    // already failed (per spec: "if the error persists after retrying once").
    var hasRetried by remember { mutableStateOf(false) }

    // Watch progress: the player reports the real playback position (every
    // ~5 s while playing + on pause/end); we persist position + duration so
    // the Media tab can show progress bars, a "Watched" badge, and Continue
    // Watching can resume from the exact position.
    val progressStore = remember { WatchProgressStore(context.applicationContext) }
    val libraryStore = remember { MediaLibraryStore(context.applicationContext) }
    // Offline audio: initialize once, then observe this video's download state
    // (snapshot state — the panel recomposes when the download progresses).
    LaunchedEffect(Unit) { AudioDownloads.initialize(context.applicationContext) }
    val downloadStatus = AudioDownloads.statusFor(video.videoId)
    val isOffline = AudioDownloads.isDownloaded(video.videoId)
    // A manually added video can be removed from the library right here — the
    // feed cards offer it too, so the player's ⋮ menu should match. Cached per
    // video: the player recomposes rapidly during playback (progress ticks),
    // and the lookup decodes the library prefs each time.
    val isManual = remember(video.videoId) { libraryStore.isManuallyAdded(video.videoId) }
    var confirmRemoveManual by remember(video.videoId) { mutableStateOf(false) }
    var lastProgressSavedAt by remember { mutableStateOf(0L) }
    // Runtime live signal: the IFrame API reports a NON-finite duration
    // (Infinity) for a live broadcast, and the JS bridge only forwards
    // progress when the duration is finite. Some live streams report the
    // stream's ELAPSED time as a finite, growing duration instead — those are
    // caught by the monotonic-growth check in onProgress. sawFiniteDuration
    // therefore only means "a bounded duration was seen at least once"; the
    // reliable live guards are the onLive bridge signal + duration growth +
    // sawPartialPlayback. `video.isLive` (thumbnail heuristic) is only a hint.
    var sawFiniteDuration by remember(video.videoId) { mutableStateOf(false) }
    // Runtime live signal from the JS bridge: the page reports an infinite
    // duration for a live broadcast (see youtube_player.html). This is the
    // RELIABLE live detection — `video.isLive` (thumbnail heuristic) rarely
    // fires for RSS feeds, so a live stream from a saved channel would
    // otherwise pass every non-live guard below and get marked watched the
    // moment the user leaves the player.
    var isLiveRuntime by remember(video.videoId) { mutableStateOf(false) }
    // Combined live state: the runtime bridge signal OR the feed's hint.
    val isLiveNow = video.isLive || isLiveRuntime
    // Duration-growth live detection: a NORMAL video reports the SAME fixed
    // duration on every progress tick, but a live broadcast that reports the
    // stream's ELAPSED time as its "duration" grows it on every tick (e.g.
    // 100s → 105s → 110s). Monotonic growth across two consecutive reports is
    // the runtime signature of a live stream that never reports the Infinity
    // the onLive bridge is keyed on — catching it here keeps such streams out
    // of watched/continue state.
    var lastReportedDuration by remember(video.videoId) { mutableDoubleStateOf(0.0) }
    // Consecutive reports where the duration GREW. Reset on any non-growing
    // report, so an in-stream ad (a one-off duration switch on a normal video)
    // can never accumulate to the live threshold.
    var growingReports by remember(video.videoId) { mutableIntStateOf(0) }
    // True once this source showed a genuinely PARTIAL position (< 98% of a
    // stable duration). A bounded video always passes through partial
    // positions while playing; an elapsed-time live stream reads ≈100% from
    // its very first report — so a near-complete fraction is only trusted
    // once this is set. This is what blocks the fast-exit path (a live stream
    // exited within seconds of opening must not be marked watched).
    var sawPartialPlayback by remember(video.videoId) { mutableStateOf(false) }
    // Bumped when progress is persisted / marked watched so the control panel
    // reflects the latest watch state (e.g. Continue Watching → Watch Again).
    var progressRevision by remember { mutableIntStateOf(0) }

    // One-shot transition into live for THIS source: purge any stale progress
    // (persisted by older builds that misclassified live streams as watched)
    // and pin isLiveRuntime so every non-live guard below switches off. The
    // bridge re-fires onLive every ~5 s while a stream plays; the guard makes
    // the purge a one-shot per video.
    fun markSourceLive() {
        if (!isLiveRuntime) {
            isLiveRuntime = true
            progressStore.remove(video.videoId)
            progressRevision++
        }
    }

    // Continue Watching state for THIS video (re-read on every revision).
    val savedProgress: VideoProgress? =
        remember(video.videoId, progressRevision) { progressStore.getProgress(video.videoId) }
    // A live broadcast has no finite duration to complete — never present it
    // as watched or resumable, even if an older build left stale progress for
    // it (misclassified live → watched bug).
    val isWatched = !isLiveNow && (savedProgress?.fraction ?: 0f) >= 0.9f
    // Continue Watching / resume applies ONLY to long videos — Shorts always
    // play from the beginning (they're watched in one sitting). Progress is
    // still tracked for Shorts (cards show the % / Watched badge).
    val hasPartialProgress =
        !isLiveNow && !video.isShort && !isWatched &&
            (savedProgress?.fraction ?: 0f) >= 0.02f
    // Auto-resume from the saved position unless the video was completed
    // (completed → start over).
    val resumeFromSeconds =
        if (hasPartialProgress) savedProgress!!.positionSeconds.toDouble() else 0.0

    // Continue Watching / Watch Again re-seek (no reload).
    var seekToken by remember { mutableIntStateOf(0) }
    var seekToSeconds by remember { mutableStateOf(0.0) }
    val requestSeek: (Double) -> Unit = { target ->
        seekToSeconds = target
        seekToken++
    }

    // Playback speed, persisted across restarts.
    val playerPrefs = remember {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var playbackRate by remember {
        mutableStateOf(playerPrefs.getFloat(KEY_PLAYBACK_RATE, 1f).toDouble())
    }
    val setPlaybackRate: (Double) -> Unit = { rate ->
        playbackRate = rate
        playerPrefs.edit().putFloat(KEY_PLAYBACK_RATE, rate.toFloat()).apply()
    }

    // User playlists (shared with the Media tab's local library). Re-read
    // fresh whenever the picker opens — the player is the only screen on top,
    // so an edit made here is picked up by the Media tab on its next
    // composition. Playlists replaced the old Bookmark feature.
    val userPlaylistStore = remember { UserPlaylistStore(context.applicationContext) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    // Same picker in AUDIO mode: the ⋮ menu's "Add audio to playlist…" seeds
    // an audio entry (MediaVideo.isOfflineAudio) instead of the video entry,
    // so the playlist gains "audio of this video" — tapping it plays the
    // downloaded file rather than opening the player.
    var showAudioPlaylistPicker by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    // True while the create dialog was opened from the AUDIO picker, so its
    // seed is the audio entry, not the video entry.
    var creatingAudioPlaylist by remember { mutableStateOf(false) }
    // Re-read whenever EITHER picker opens (the audio picker never flips
    // showPlaylistPicker, so keying on it alone would hand the audio sheet an
    // empty list unless the video picker had been opened first).
    val playerPlaylists = remember(showPlaylistPicker, showAudioPlaylistPicker) {
        if (showPlaylistPicker || showAudioPlaylistPicker) {
            userPlaylistStore.getPlaylists()
        } else {
            emptyList()
        }
    }
    // A user playlist containing the current video — when one exists, the ⋮
    // menu offers removing the video from it (mirroring the Media tab's
    // playlist-feed cards). Bumped after any playlist edit made here so the
    // lookup stays fresh (a video added to a playlist from this screen gets
    // its Remove entry immediately).
    var playlistRevision by remember { mutableIntStateOf(0) }
    val containingPlaylist = remember(userPlaylistStore, video.videoId, playlistRevision) {
        userPlaylistStore.getPlaylists().firstOrNull { p ->
            // The VIDEO entry only — a playlist may hold this video's
            // downloaded AUDIO (isOfflineAudio) as a separate entry, and this
            // ⋮ entry removes the VIDEO. The audio entry is removed from the
            // playlist itself (or via the Delete download flow).
            p.videos.any { !it.isOfflineAudio && it.videoId == video.videoId }
        }
    }
    // A playlist awaiting "remove from playlist" confirmation — captured at
    // menu-tap time (like MediaTab's pendingVideoRemove) so the dialog never
    // depends on later state.
    var pendingRemovePlaylist by remember(video.videoId) { mutableStateOf<UserPlaylist?>(null) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showHideConfirm by remember { mutableStateOf(false) }
    // The ⋮ menu's "Delete download" asks first (never deletes by accident).
    var confirmDeleteDownload by remember { mutableStateOf(false) }

    // Player commands for the Shorts viewer (play/pause/mute). Each command is
    // a token + label pair: bumping the token re-sends the command to the page.
    var commandToken by remember { mutableIntStateOf(0) }
    var command by remember { mutableStateOf("") }
    val sendCommand: (String) -> Unit = { cmd ->
        command = cmd
        commandToken++
    }
    // Muted state: starts from the PERSISTED preference (default true) and is
    // remembered across videos, so muting/unmuting one Short carries to every
    // Short you swipe to. The JS bridge reports the player's real state and
    // the toggle applies + persists the change immediately.
    var isMuted by remember {
        mutableStateOf(playerPrefs.getBoolean(KEY_MUTED, true))
    }

    fun shareVideo() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Watch: ${video.title}\nhttps://www.youtube.com/watch?v=${video.videoId}"
            )
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "Share video"))
        }
    }

    // Reset retry history when a different video is selected (NOT on retry).
    // NOTE: the mute preference is deliberately NOT reset here — the user's
    // choice carries across videos (all Shorts stay muted/unmuted).
    LaunchedEffect(video.videoId) {
        hasRetried = false
    }

    // Reset state when a different video is selected.
    LaunchedEffect(video.videoId, retryToken) {
        playerState = YtState.UNSTARTED
        errorCode = null
        timedOut = false
        sourceStarted = false
        // Safety net: if the IFrame API never delivers any event (e.g. the
        // api script can't load — no network), surface an error instead of
        // an eternal spinner.
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            if (playerState == YtState.PLAYING ||
                playerState == YtState.PAUSED ||
                errorCode != null
            ) {
                return@LaunchedEffect
            }
        }
        if (playerState != YtState.PLAYING && playerState != YtState.PAUSED &&
            errorCode == null
        ) {
            timedOut = true
        }
    }

    // Loading: no real state yet — the placeholder (blurred thumbnail) is
    // shown until the player actually starts or is paused. CUED is included:
    // a cued-but-still-buffering video would otherwise show the embed's own
    // oversized play graphic over a black surface.
    val isBuffering = errorCode == null && !timedOut &&
        (playerState == YtState.UNSTARTED ||
            playerState == YtState.BUFFERING ||
            playerState == YtState.CUED)
    // Ready but paused (incl. muted-autoplay blocked): a small centered play
    // button, shown ONLY once the video is actually ready.
    val showPlayOverlay = errorCode == null && !timedOut && !isBuffering &&
        playerState == YtState.PAUSED
    // Ready-but-paused BEFORE this source ever started playing (autoplay
    // blocked — e.g. Android data-saver). The blurred placeholder must stay up
    // here too so the embed's grey play button can never show; the app's own
    // centered play overlay (Shorts only) is the play affordance on top. Long
    // videos keep their reachable embed controls, so they are NOT blurred in
    // this state. Guarded against errors/timeouts so the blur can never cover
    // an error card.
    val pausedNotStarted = errorCode == null && !timedOut &&
        video.isShort && playerState == YtState.PAUSED

    Column(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        // ── Video area: player + its overlays, nothing else in this box ──
        // Exactly matches the WebView's bounds so no sibling can cover it.
        // Vertical fullscreen (Shorts style) fills the whole portrait screen;
        // landscape is naturally full screen; otherwise a 16:9 box at the top.
        Box(
            modifier = when {
                isLandscape || fullscreenVertical -> Modifier.fillMaxSize()
                else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            }
        ) {
            YoutubePlayer(
                videoId = video.videoId,
                retryToken = retryToken,
                resumeFromSeconds = resumeFromSeconds,
                playbackRate = playbackRate,
                seekToken = seekToken,
                seekToSeconds = seekToSeconds,
                commandToken = commandToken,
                command = command,
                muted = isMuted,
                modifier = Modifier.fillMaxSize(),
                onMuteState = { muted -> isMuted = muted },
                onPlayerState = { s ->
                    // Late connection: the source is now usable — drop any
                    // timeout card.
                    if (s == YtState.PLAYING || s == YtState.PAUSED) {
                        timedOut = false
                    }
                    // sourceStarted flips ONLY on PLAYING: a ready-but-paused
                    // video (autoplay blocked before it ever started) must keep
                    // the blurred placeholder + the app's own play overlay
                    // (Shorts) instead of exposing the embed's grey play
                    // button. A mid-playback pause still has sourceStarted =
                    // true, so it never re-blurs.
                    if (s == YtState.PLAYING) {
                        sourceStarted = true
                    }
                    playerState = s
                    // Video finished: the whole thing counts as watched. Live
                    // broadcasts can report ENDED when the user simply leaves
                    // the player — a live stream has no finite duration to
                    // complete, so it must never be marked watched. The runtime
                    // live signal (isLiveNow) catches live streams even when
                    // the thumbnail-based isLive hint missed them, and
                    // sawPartialPlayback requires the source to have actually
                    // played through partial positions first (an elapsed-time
                    // live stream reads ≈100% from the start, so it never
                    // satisfies this).
                    if (s == YtState.ENDED && !isLiveNow &&
                        sawFiniteDuration && sawPartialPlayback
                    ) {
                        progressStore.set(video.videoId, 1f)
                        progressRevision++
                    }
                },
                onLive = {
                    // First time THIS source reports live: purge any stale
                    // progress persisted by older builds (which misclassified
                    // live streams as watched) so the Watched badge and
                    // Continue-Watching state clear immediately.
                    markSourceLive()
                },
                onProgress = { currentSeconds, durationSeconds ->
                    // A finite duration report is the runtime proof this is a
                    // bounded (non-live) video — live streams never report one
                    // (they report Infinity or the growing elapsed time, both
                    // handled here).
                    if (durationSeconds > 0) {
                        // Elapsed-time live detection: a broadcast that reports
                        // the stream's elapsed time as its duration GROWS it on
                        // every tick, monotonically. Two consecutive growing
                        // reports = live, even when the Infinity signal never
                        // fires. Any non-growing report (stable VOD duration, or
                        // an in-stream ad's one-off duration switch) resets the
                        // counter, so ads can't trigger a false positive.
                        if (lastReportedDuration > 0.0) {
                            if (durationSeconds - lastReportedDuration > 1.0) {
                                growingReports++
                                if (growingReports >= 2) {
                                    markSourceLive()
                                    return@YoutubePlayer
                                }
                            } else {
                                growingReports = 0
                            }
                        }
                        lastReportedDuration = durationSeconds
                        sawFiniteDuration = true
                    }
                    // Live streams are excluded from progress tracking entirely:
                    // the IFrame API reports a live/unknown duration, which
                    // would render a fake progress bar and could pin the video
                    // as watched. Once the stream ends and becomes a VOD it is
                    // re-parsed as a normal video and tracked normally.
                    if (!isLiveNow && durationSeconds > 0) {
                        // While a resume seek is still landing, early reports
                        // can read ~0 and would overwrite the saved position.
                        // Skip until the position actually reaches the target.
                        if (resumeFromSeconds > 3.0 &&
                            currentSeconds < resumeFromSeconds - 3.0
                        ) {
                            return@YoutubePlayer
                        }
                        val fraction = (currentSeconds / durationSeconds)
                            .toFloat().coerceIn(0f, 1f)
                        if (fraction < 0.98f) sawPartialPlayback = true
                        // A near-complete fraction is only trusted once the
                        // source showed genuinely partial playback — an
                        // elapsed-time live stream reads ≈100% from the very
                        // first report, so trusting it here would mark the
                        // stream watched even before the growth check runs.
                        if (fraction >= 0.98f && !sawPartialPlayback) {
                            return@YoutubePlayer
                        }
                        val now = System.currentTimeMillis()
                        if (fraction >= 0.98f || now - lastProgressSavedAt >= 5_000L) {
                            progressStore.setProgress(
                                video.videoId,
                                fraction,
                                currentSeconds.toLong(),
                                durationSeconds.toLong()
                            )
                            lastProgressSavedAt = now
                        }
                    }
                },
                onPlayerError = { code ->
                    // Log ONCE per error code (the bridge dedups repeats) with the
                    // video id, then update the UI state once.
                    Log.w(TAG, "YouTube player error code = $code videoId = ${video.videoId}")
                    timedOut = false
                    errorCode = code
                }
            )

            // ── Shorts vertical swipe navigation (full-screen viewer) ──
            // A transparent layer ABOVE the WebView translates vertical drags
            // into next/previous Shorts. Taps pass through to the player's
            // own controls (only drags are consumed). Drawn below the
            // fullscreen button + error overlays so those stay tappable.
            if (fullscreenVertical && shortsQueue.size > 1) {
                val swipeThreshold = with(LocalDensity.current) { 60.dp.toPx() }
                // CRITICAL: pointerInput(Unit) launches its gesture block ONCE
                // and never restarts, so plain captures of shortsIndex / queue
                // size would be frozen at the moment the layer first appeared
                // (the first short opened). The "previous" guard (index > 0)
                // would then stay permanently false and swiping back would
                // never work. rememberUpdatedState keeps both values live.
                val currentIndex by rememberUpdatedState(shortsIndex)
                val currentQueueSize by rememberUpdatedState(shortsQueue.size)
                var swipeAccum by remember { mutableStateOf(0f) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    swipeAccum += dragAmount
                                },
                                onDragEnd = {
                                    when {
                                        swipeAccum <= -swipeThreshold &&
                                            currentIndex < currentQueueSize - 1 ->
                                            onNavigateShorts(1)
                                        swipeAccum >= swipeThreshold && currentIndex > 0 ->
                                            onNavigateShorts(-1)
                                    }
                                    swipeAccum = 0f
                                },
                                onDragCancel = { swipeAccum = 0f }
                            )
                        }
                )
                // Position counter pill (e.g. "3 / 12").
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = "${(shortsIndex + 1).coerceAtLeast(1)} / ${shortsQueue.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // ── Shorts viewer transport bar: play/pause + mute (the iframe's
            // own controls are hidden behind the swipe layer, so the viewer
            // gets its own always-visible controls). Drawn ABOVE the swipe
            // layer so the buttons stay tappable. Shown ONLY for Shorts — a
            // long video in vertical fullscreen has no swipe layer, so the
            // embed's own on-video controls remain reachable.
            if (fullscreenVertical && shortsQueue.isNotEmpty()) {
                ShortsControlBar(
                    isPlaying = playerState == YtState.PLAYING,
                    isMuted = isMuted,
                    canGoPrevious = shortsIndex > 0,
                    canGoNext = shortsIndex < shortsQueue.size - 1,
                    onTogglePlay = {
                        sendCommand(if (playerState == YtState.PLAYING) "pause" else "play")
                    },
                    onToggleMute = {
                        val target = !isMuted
                        isMuted = target
                        playerPrefs.edit().putBoolean(KEY_MUTED, target).apply()
                        sendCommand(if (target) "mute" else "unmute")
                    },
                    onSeekBack = { sendCommand("back10") },
                    onSeekForward = { sendCommand("fwd10") },
                    onPrevious = { onNavigateShorts(-1) },
                    onNext = { onNavigateShorts(1) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                )
            }

            // ── Loading placeholder: a blurred thumbnail of the actual video
            // (or a plain dark box) that fades away when playback first starts.
            // No icon, no spinner, no text — the video area stays clean.
            // ALWAYS composed (never disposed on source change) so the blur
            // can't "pop" off the instant PLAYING fires: it fades OUT over
            // 400ms, keeping the screen covered while the new video's first
            // real frame renders — closing the 1-frame gap where the embed's
            // own grey play button would flash during Shorts swipes (the JS
            // #loading-cover in youtube_player.html backstops it too). Only
            // visible until the source has started once (sourceStarted):
            // buffering during a forward/backward seek re-uses the real
            // frames and must never be covered by it. The ready-but-paused
            // state (autoplay blocked before start) is covered too, so the
            // embed's grey play button never shows there either.
            LoadingPlaceholderOverlay(
                visible = !sourceStarted && (isBuffering || pausedNotStarted),
                thumbnailUrl = placeholderThumbnail,
                onThumbnailLoaded = { if (video.thumbnailUrl == placeholderThumbnail) {
                    currentThumbnailReady = true
                } },
                modifier = Modifier.fillMaxSize()
            )

            // ── Ready-but-paused: a single centered play button (only shown
            // once the video is ready, so there's never a play graphic during
            // the loading phase). Tapping it resumes playback. Shown ONLY for
            // Shorts — long videos keep the embed's own on-video controls
            // (which are reachable without the swipe layer), so a duplicate
            // overlay button is unnecessary there.
            if (showPlayOverlay && video.isShort) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(68.dp)
                        .clickable { sendCommand("play") },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Fullscreen toggle (top-right, over the video): in portrait it
            // switches to a YouTube Shorts-style vertical fullscreen (video
            // fills the whole screen, bars hide); tapping again (or back)
            // returns to the normal 16:9 layout. In landscape the video is
            // already fullscreen, so the button rotates back to portrait.
            // Rotation never restarts playback — the activities declare
            // configChanges, so the WebView survives it.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.4f)
            ) {
                IconButton(
                    onClick = {
                        if (isLandscape) {
                            // Leaving landscape fullscreen returns to the
                            // normal portrait layout: also clear any vertical
                            // fullscreen that was active before the rotation,
                            // otherwise the video stays fullscreen after
                            // rotating back.
                            if (fullscreenVertical) onToggleFullscreen()
                            activity?.requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            onToggleFullscreen()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (isLandscape || fullscreenVertical)
                            Icons.Filled.FullscreenExit
                        else
                            Icons.Filled.Fullscreen,
                        contentDescription = if (isLandscape || fullscreenVertical)
                            "Exit fullscreen"
                        else
                            "Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (timedOut) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.media_player_timeout),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    PlayerErrorActions(
                        videoId = video.videoId,
                        showOpenInYoutube = hasRetried,
                        onRetry = {
                            hasRetried = true
                            retryToken++
                        }
                    )
                }
            }

            if (errorCode != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = stringResource(errorMessageRes(errorCode!!)),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    PlayerErrorActions(
                        videoId = video.videoId,
                        showOpenInYoutube = hasRetried,
                        onRetry = {
                            hasRetried = true
                            retryToken++
                        }
                    )
                }
            }
        }

        // ── Control panel: BELOW the video area in portrait — it can never
        // cover the player. Hidden in landscape and in vertical fullscreen
        // (the video fills the screen).
        if (!isLandscape && !fullscreenVertical) {
            PlayerControlPanel(
                video = video,
                isLive = isLiveNow,
                progress = savedProgress,
                isWatched = isWatched,
                hasPartialProgress = hasPartialProgress,
                playbackRate = playbackRate,
                showSpeedMenu = showSpeedMenu,
                downloadStatus = downloadStatus,
                isOffline = isOffline,
                isManual = isManual,
                onDownloadAudio = {
                    AudioDownloads.download(video, AudioDownloads.sourceFor(video))
                },
                onPlayOffline = onPlayOffline,
                onDeleteDownload = { confirmDeleteDownload = true },
                onRemoveManual = { confirmRemoveManual = true },
                onDownloadMenuAction = {
                    if (downloadStatus is DownloadStatus.Preparing ||
                        downloadStatus is DownloadStatus.Downloading
                    ) {
                        AudioDownloads.cancel(video.videoId)
                    } else {
                        AudioDownloads.download(video, AudioDownloads.sourceFor(video))
                    }
                },
                onAddToPlaylist = { showPlaylistPicker = true },
                onAddAudioToPlaylist = { showAudioPlaylistPicker = true },
                onRemoveFromPlaylist = { pendingRemovePlaylist = containingPlaylist },
                containingPlaylistName = containingPlaylist?.name,
                onSpeedMenuToggle = { showSpeedMenu = !showSpeedMenu },
                onSpeedSelect = { setPlaybackRate(it); showSpeedMenu = false },
                onContinue = { requestSeek(resumeFromSeconds) },
                onWatchAgain = { requestSeek(0.0) },
                onShare = { shareVideo() },
                onHide = { showHideConfirm = true },
                onMarkWatched = {
                    progressStore.set(video.videoId, 1f)
                    progressRevision++
                    Toast.makeText(context, "Marked as watched", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    // ── Hide confirmation ───────────────────────────────────────────
    if (showHideConfirm) {
        AlertDialog(
            onDismissRequest = { showHideConfirm = false },
            title = { Text("Hide this video?") },
            text = { Text("This video will be removed from your feeds.") },
            confirmButton = {
                TextButton(onClick = {
                    libraryStore.hideVideo(video)
                    showHideConfirm = false
                    Toast.makeText(context, "Video hidden", Toast.LENGTH_SHORT).show()
                    onExit()
                }) { Text("Hide", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showHideConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // ── Remove manually added video confirmation (⋮ menu → Remove) ──
    if (confirmRemoveManual) {
        AlertDialog(
            onDismissRequest = { confirmRemoveManual = false },
            title = { Text("Remove this video?") },
            text = { Text("\"${video.title}\" was added manually. Removing it deletes it from your library.") },
            confirmButton = {
                TextButton(onClick = {
                    libraryStore.removeManuallyAdded(video.videoId)
                    confirmRemoveManual = false
                    Toast.makeText(context, "Video removed", Toast.LENGTH_SHORT).show()
                    onExit()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveManual = false }) { Text("Cancel") }
            }
        )
    }

    // ── Remove from playlist confirmation (⋮ menu → Remove from …) ──
    // Removing a video from a playlist never touches the video itself, so the
    // player stays open — the menu entry simply disappears afterwards.
    pendingRemovePlaylist?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingRemovePlaylist = null },
            title = { Text("Remove video?") },
            text = { Text("Remove \"${video.title}\" from \"${playlist.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    userPlaylistStore.removeVideo(playlist.id, video.videoId)
                    playlistRevision++
                    pendingRemovePlaylist = null
                    Toast.makeText(
                        context,
                        "Removed from ${playlist.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemovePlaylist = null }) { Text("Cancel") }
            }
        )
    }

    // ── Delete offline audio confirmation (⋮ menu → Delete download) ──
    if (confirmDeleteDownload) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDownload = false },
            title = { Text("Delete download?") },
            text = { Text("Delete the offline audio of \"${video.title}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    AudioDownloads.delete(video.videoId)
                    confirmDeleteDownload = false
                    Toast.makeText(context, "Download deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDownload = false }) { Text("Cancel") }
            }
        )
    }

    // ── Add to playlist (Playlist button / ⋮ menu) ──────────────────
    // Reuses the Media tab's picker + name dialog (same package). Adding a
    // video here is instantly visible in the Media tab's playlist feed.
    if (showPlaylistPicker) {
        AddToPlaylistSheet(
            video = video,
            playlists = playerPlaylists,
            onAdd = { playlist ->
                userPlaylistStore.addVideos(playlist.id, listOf(video))
                playlistRevision++
                showPlaylistPicker = false
                Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
            },
            onCreateNew = {
                showPlaylistPicker = false
                showCreatePlaylistDialog = true
            },
            onDismiss = { showPlaylistPicker = false }
        )
    }
    if (showAudioPlaylistPicker) {
        // Audio mode of the same picker: seeds an AUDIO entry so the playlist
        // holds "audio of this video" (plays the downloaded file). Only
        // reachable while the audio is downloaded (⋮ menu gates on isOffline).
        AddToPlaylistSheet(
            video = video.copy(isOfflineAudio = true),
            title = "Add audio to playlist",
            newLabel = "New playlist with this audio",
            playlists = playerPlaylists,
            onAdd = { playlist ->
                userPlaylistStore.addVideos(
                    playlist.id,
                    listOf(video.copy(isOfflineAudio = true))
                )
                playlistRevision++
                showAudioPlaylistPicker = false
                Toast.makeText(
                    context,
                    "Added audio to ${playlist.name}",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onCreateNew = {
                showAudioPlaylistPicker = false
                creatingAudioPlaylist = true
                showCreatePlaylistDialog = true
            },
            onDismiss = { showAudioPlaylistPicker = false }
        )
    }
    if (showCreatePlaylistDialog) {
        PlaylistNameDialog(
            initial = "",
            title = "New playlist",
            confirmLabel = "Create",
            onSubmit = { name ->
                val seed =
                    if (creatingAudioPlaylist) listOf(video.copy(isOfflineAudio = true))
                    else listOf(video)
                creatingAudioPlaylist = false
                userPlaylistStore.createPlaylist(name, seed)
                playlistRevision++
                showCreatePlaylistDialog = false
                Toast.makeText(
                    context,
                    if (seed.first().isOfflineAudio) {
                        "Created \"$name\" with this audio"
                    } else {
                        "Created \"$name\" with this video"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDismiss = {
                creatingAudioPlaylist = false
                showCreatePlaylistDialog = false
            }
        )
    }
}

/**
 * The dedicated controls area BELOW the video (portrait): title + channel,
 * the primary Continue Watching / Watch Again action, and a four-button
 * action row (⋮ More with Add to playlist / Download audio / Hide / Mark as
 * watched, Share, Speed, Playlist). Keeps every secondary action off the
 * video itself.
 */
@Composable
private fun PlayerControlPanel(
    video: MediaVideo,
    isLive: Boolean,
    progress: VideoProgress?,
    isWatched: Boolean,
    hasPartialProgress: Boolean,
    playbackRate: Double,
    showSpeedMenu: Boolean,
    downloadStatus: DownloadStatus?,
    isOffline: Boolean,
    /** Whether this video was manually added by URL (its ⋮ menu offers Remove). */
    isManual: Boolean,
    onDownloadAudio: () -> Unit,
    onPlayOffline: () -> Unit,
    onDeleteDownload: () -> Unit,
    /** ⋮ menu download entry: cancel while active, else start/retry. */
    onDownloadMenuAction: () -> Unit,
    onAddToPlaylist: () -> Unit,
    /** ⋮ menu → Add audio to playlist (only while the audio is downloaded). */
    onAddAudioToPlaylist: () -> Unit,
    /** ⋮ menu → Remove from playlist (only when [containingPlaylistName] is set). */
    onRemoveFromPlaylist: () -> Unit,
    /** Name of the user playlist holding this video, or null (no entry shown). */
    containingPlaylistName: String?,
    onSpeedMenuToggle: () -> Unit,
    onSpeedSelect: (Double) -> Unit,
    onContinue: () -> Unit,
    onWatchAgain: () -> Unit,
    onShare: () -> Unit,
    onHide: () -> Unit,
    /** ⋮ menu → Remove (manually added). */
    onRemoveManual: () -> Unit,
    onMarkWatched: () -> Unit
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        // Title + channel · time.
        Text(
            text = video.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                append(video.channelName.ifBlank { video.channelId })
                if (video.publishedAtEpochMillis > 0L) {
                    append(" · ").append(
                        DateUtils.getRelativeTimeSpanString(
                            video.publishedAtEpochMillis,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString()
                    )
                }
                val duration = progress?.durationSeconds ?: 0L
                if (duration > 0L) {
                    append(" · ").append(formatPosition(duration))
                }
                if (duration > 0L && !isWatched && hasPartialProgress) {
                    append(" · ").append("${((progress?.fraction ?: 0f) * 100).toInt()}%")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Primary action: Continue Watching / Watch Again.
        if (hasPartialProgress) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Continue Watching · " +
                        formatPosition(progress?.positionSeconds ?: 0L)
                )
            }
        } else if (isWatched) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onWatchAgain,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Watch Again")
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(4.dp))

        // ── Action row ──
        Row(modifier = Modifier.fillMaxWidth()) {
            // ⋮ More: Hide video / Mark as watched.
            Box(modifier = Modifier.weight(1f)) {
                PanelAction(
                    icon = Icons.Filled.MoreVert,
                    label = "More",
                    onClick = { showMoreMenu = true },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Add to playlist…") },
                        onClick = {
                            showMoreMenu = false
                            onAddToPlaylist()
                        }
                    )
                    // The downloaded audio can ALSO be added to a playlist — as
                    // its own AUDIO entry (plays the offline file) next to the
                    // video entry above. Only while the audio exists.
                    if (isOffline) {
                        DropdownMenuItem(
                            text = { Text("Add audio to playlist…") },
                            onClick = {
                                showMoreMenu = false
                                onAddAudioToPlaylist()
                            }
                        )
                    }
                    if (containingPlaylistName != null) {
                        DropdownMenuItem(
                            text = { Text("Remove from \"$containingPlaylistName\"") },
                            onClick = {
                                showMoreMenu = false
                                onRemoveFromPlaylist()
                            }
                        )
                    }
                    if (isOffline) {
                        DropdownMenuItem(
                            text = { Text("Delete download") },
                            onClick = {
                                showMoreMenu = false
                                onDeleteDownload()
                            }
                        )
                    }
                    // Audio download with its stateful label, mirroring the feed
                    // cards' ⋮ menu (Download audio / Cancel / Retry). Hidden once
                    // offline (Play Offline owns that state) and for live streams
                    // (they can't be downloaded).
                    if (!isOffline && !isLive) {
                        DropdownMenuItem(
                            text = { Text(downloadMenuLabel(downloadStatus, false)) },
                            onClick = {
                                showMoreMenu = false
                                onDownloadMenuAction()
                            }
                        )
                    }
                    if (isManual) {
                        DropdownMenuItem(
                            text = { Text("Remove (manually added)") },
                            onClick = {
                                showMoreMenu = false
                                onRemoveManual()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Hide video") },
                        onClick = {
                            showMoreMenu = false
                            onHide()
                        }
                    )
                    // A live broadcast has no finite duration to complete, so
                    // "Mark as watched" is meaningless for it.
                    if (!isLive) {
                        DropdownMenuItem(
                            text = { Text("Mark as watched") },
                            onClick = {
                                showMoreMenu = false
                                onMarkWatched()
                            }
                        )
                    }
                }
            }
            PanelAction(
                icon = Icons.Filled.Share,
                label = "Share",
                onClick = onShare,
                modifier = Modifier.weight(1f)
            )
            // Speed hosts its own dropdown menu.
            Box(modifier = Modifier.weight(1f)) {
                PanelAction(
                    icon = Icons.Filled.Speed,
                    label = "Speed",
                    onClick = onSpeedMenuToggle,
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = onSpeedMenuToggle
                ) {
                    SPEED_OPTIONS.forEach { rate ->
                        DropdownMenuItem(
                            text = { Text(formatRate(rate)) },
                            trailingIcon = if (rate == playbackRate) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                            onClick = { onSpeedSelect(rate) }
                        )
                    }
                }
            }
            PanelAction(
                icon = Icons.Filled.PlaylistAdd,
                label = "Playlist",
                onClick = onAddToPlaylist,
                modifier = Modifier.weight(1f)
            )
        }

        // ── Offline audio: the Download Audio button with its full state flow
        // (Download → Preparing… → Downloading NN% → Downloaded → Play offline).
        // Live broadcasts can't be downloaded, so the button is hidden for them.
        // The resolved audio size (≈ X MB) appears as soon as the stream is
        // resolved — before any bytes are downloaded.
        if (!isLive) {
            val audioSize = AudioDownloads.pendingSizes[video.videoId]
            val sizeSuffix = if (audioSize != null && audioSize > 0L)
                " · ≈ ${formatBytes(audioSize)}" else ""
            Spacer(Modifier.height(10.dp))
            when {
                isOffline -> FilledTonalButton(
                    onClick = onPlayOffline,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Play Offline")
                }
                downloadStatus is DownloadStatus.Preparing -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Preparing…$sizeSuffix")
                }
                downloadStatus is DownloadStatus.Downloading -> Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (downloadStatus.progress >= 0f)
                                "Downloading ${(downloadStatus.progress.coerceIn(0f, 1f) * 100).toInt()}%$sizeSuffix"
                            else
                                "Downloading…$sizeSuffix"
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Estimated time remaining, once the downloader has enough
                    // history to compute it ("~2m 30s left").
                    val etaText = formatEtaRemaining(downloadStatus.etaSeconds)
                    if (etaText.isNotEmpty()) {
                        Text(
                            text = etaText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (downloadStatus.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { downloadStatus.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                    }
                }
                downloadStatus is DownloadStatus.Error -> Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onDownloadAudio,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Retry download")
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = downloadStatus.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2
                    )
                }
                // Tonal (not primary-filled like Continue Watching) so the audio
                // action reads as a separate, secondary step.
                else -> FilledTonalButton(
                    onClick = onDownloadAudio,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Download Audio")
                }
            }
        }
    }
}

/**
 * Fades the loading placeholder in/out. Wrapped in its own composable so the
 * [AnimatedVisibility] call resolves to the top-level overload (inside the
 * video Box the ColumnScope extension would otherwise be ambiguous).
 *
 * [thumbnailUrl] switches between the previous video's thumbnail (while the
 * new one loads) and the new one (once ready) — a [Crossfade] inside makes the
 * swap a smooth blur-to-blur transition instead of a blank/flashing frame.
 *
 * Enter is INSTANT: the moment a new source is opened or swiped to, the blur
 * must be fully opaque on the very first frame — a fade-in window would let
 * YouTube's grey play-button poster show through underneath. Exit stays slow
 * (400ms): when PLAYING fires the video's first frame may not be composited
 * for another frame or two, so the blur eases away and the transition lands
 * on real video, never on the grey play button.
 */
@Composable
private fun LoadingPlaceholderOverlay(
    visible: Boolean,
    thumbnailUrl: String?,
    onThumbnailLoaded: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(0)),
        exit = fadeOut(animationSpec = tween(400)),
        modifier = modifier
    ) {
        Crossfade(
            targetState = thumbnailUrl,
            animationSpec = tween(300),
            label = "loading-placeholder-blur"
        ) { url ->
            VideoLoadingPlaceholder(
                thumbnailUrl = url.orEmpty(),
                onLoaded = onThumbnailLoaded
            )
        }
    }
}

/**
 * The loading placeholder shown over the player until the video is ready: a
 * blurred, darkened thumbnail of the video poster (no icon, no spinner,
 * no text) or a plain dark gradient when no thumbnail is available. The video
 * fades in beneath it, so there's no layout shift.
 */
@Composable
private fun VideoLoadingPlaceholder(
    thumbnailUrl: String,
    onLoaded: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black)) {
        if (thumbnailUrl.isNotBlank()) {
            RemoteImage(
                url = thumbnailUrl,
                showLoadingSpinner = false,
                onLoaded = onLoaded,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.15f) // cover the blur's soft edges
                    .blur(18.dp)
            )
            // Darken so it reads as a placeholder, not the actual poster.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        } else {
            // No thumbnail yet — a soft dark gradient instead of a flat,
            // jarring black screen.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xFF141414),
                            1f to Color(0xFF000000)
                        )
                    )
            )
        }
    }
}

/** One cell of the action row: icon over a small label, tap target ~48dp. */
@Composable
private fun PanelAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * The Shorts fullscreen transport bar: previous / play-pause / mute / next.
 * The IFrame player's own on-video controls are unreachable behind the swipe
 * layer, so this always-visible bar provides the essential controls.
 */
@Composable
private fun ShortsControlBar(
    isPlaying: Boolean,
    isMuted: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onTogglePlay: () -> Unit,
    onToggleMute: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = Color.Black.copy(alpha = 0.6f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            TransportIconButton(
                icon = Icons.Filled.KeyboardArrowUp,
                label = "Previous short",
                enabled = canGoPrevious,
                onClick = onPrevious
            )
            TransportIconButton(
                icon = Icons.Filled.Replay10,
                label = "Back 10 seconds",
                enabled = true,
                onClick = onSeekBack
            )
            TransportIconButton(
                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                label = if (isPlaying) "Pause" else "Play",
                enabled = true,
                onClick = onTogglePlay,
                emphasized = true
            )
            TransportIconButton(
                icon = Icons.Filled.Forward10,
                label = "Forward 10 seconds",
                enabled = true,
                onClick = onSeekForward
            )
            TransportIconButton(
                icon = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                label = if (isMuted) "Unmute" else "Mute",
                enabled = true,
                onClick = onToggleMute
            )
            TransportIconButton(
                icon = Icons.Filled.KeyboardArrowDown,
                label = "Next short",
                enabled = canGoNext,
                onClick = onNext
            )
        }
    }
}

@Composable
private fun TransportIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    emphasized: Boolean = false
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(if (emphasized) 60.dp else 52.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(if (emphasized) 34.dp else 28.dp)
        )
    }
}

/** "12:34", or "1:02:34" past an hour. */
private fun formatPosition(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, sec)
    } else {
        "%02d:%02d".format(m, sec)
    }
}

/** "1.0x", "1.25x", … (whole values rendered without a trailing .0). */
internal fun formatRate(rate: Double): String =
    (if (rate == rate.toLong().toDouble()) rate.toLong().toString() else rate.toString()) + "x"

/** The playback-speed options offered by the players. */
internal val SPEED_OPTIONS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

/** SharedPreferences holding the user's persisted playback rate. */
private const val PREFS_NAME = "media_player_prefs"
private const val KEY_PLAYBACK_RATE = "playback_rate"
private const val KEY_MUTED = "muted"

private const val TAG = "VideoPlayerScreen"

/** If no player event arrives within this window, surface an error card. */
private const val LOAD_TIMEOUT_MS = 25_000L

/**
 * Actions under a playback-failure card: retry in-app, plus an OPT-IN
 * "Open in YouTube app" — launched only when the user taps it (never
 * automatic), for the case where YouTube's embed restrictions can't be
 * worked around from the WebView (e.g. error 152).
 */
@Composable
private fun PlayerErrorActions(
    videoId: String,
    showOpenInYoutube: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.media_player_retry))
        }
        // Opt-in escape hatch, shown only after an in-app retry already failed.
        if (showOpenInYoutube) {
            TextButton(onClick = { openInYouTubeApp(context, videoId) }) {
                Text(stringResource(R.string.media_player_open_youtube))
            }
        }
    }
}

/**
 * Explicit user action: YouTube app first, any other handler (browser) as a
 * graceful fallback. Both startActivity calls are guarded — a device with
 * neither installed must not crash the app.
 */
private fun openInYouTubeApp(context: Context, videoId: String) {
    val watchUri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
    val ytApp = Intent(Intent.ACTION_VIEW, watchUri)
        .setPackage("com.google.android.youtube")
    try {
        context.startActivity(ytApp)
        return
    } catch (e: ActivityNotFoundException) {
        // YouTube app not installed — fall through to a generic handler.
    } catch (e: SecurityException) {
        // Ignore and fall through.
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, watchUri))
    } catch (e: Exception) {
        // No handler at all — nothing we can do; stay in-app.
    }
}

/** Maps a YouTube IFrame API error code to a user-facing message. */
private fun errorMessageRes(code: Int): Int = when (code) {
    // 152 is YouTube's player-config error, seen when the video cannot be
    // played in the embedded player (removed / region / age restrictions).
    YtError.VIDEO_UNAVAILABLE -> R.string.media_player_error_unavailable
    YtError.VIDEO_NOT_FOUND -> R.string.media_player_error_not_found
    YtError.EMBED_NOT_ALLOWED, YtError.EMBED_NOT_ALLOWED_2 ->
        R.string.media_player_error_embed_restricted
    YtError.INVALID_PARAMETER, YtError.HTML5_PLAYER -> R.string.media_player_error_playback
    else -> R.string.media_player_error_playback
}
