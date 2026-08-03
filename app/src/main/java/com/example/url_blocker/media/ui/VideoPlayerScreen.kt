package com.example.url_blocker.media.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.url_blocker.R
import com.example.url_blocker.media.data.WatchProgressStore
import com.example.url_blocker.media.model.MediaVideo
import kotlinx.coroutines.delay

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
 */
@Composable
fun VideoPlayerScreen(
    video: MediaVideo,
    isLandscape: Boolean,
    fullscreenVertical: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var playerState by remember { mutableStateOf(YtState.UNSTARTED) }
    var errorCode by remember { mutableStateOf<Int?>(null) }
    var autoplayBlocked by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var retryToken by remember { mutableStateOf(0) }
    // The "Open in YouTube app" option appears only after an in-app Retry has
    // already failed (per spec: "if the error persists after retrying once").
    var hasRetried by remember { mutableStateOf(false) }

    // Watch progress: the player reports the real playback position (every
    // ~5 s while playing + on pause/end); we persist a throttled fraction so
    // the Media tab can show progress bars and a "Watched" badge.
    val progressStore = remember { WatchProgressStore(context.applicationContext) }
    var lastProgressSavedAt by remember { mutableStateOf(0L) }

    // Reset retry history when a different video is selected (NOT on retry).
    LaunchedEffect(video.videoId) {
        hasRetried = false
    }

    // Reset state when a different video is selected.
    LaunchedEffect(video.videoId, retryToken) {
        playerState = YtState.UNSTARTED
        errorCode = null
        autoplayBlocked = false
        timedOut = false
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

    val isBuffering = errorCode == null && !timedOut &&
        (playerState == YtState.UNSTARTED || playerState == YtState.BUFFERING)
    val showTapToPlay = autoplayBlocked && errorCode == null && !timedOut &&
        playerState == YtState.PAUSED

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
                modifier = Modifier.fillMaxSize(),
                onPlayerState = { s ->
                    // Late connection: playback finally started — drop any timeout card.
                    if (s == YtState.PLAYING || s == YtState.PAUSED) timedOut = false
                    playerState = s
                    // Video finished: the whole thing counts as watched.
                    if (s == YtState.ENDED) progressStore.set(video.videoId, 1f)
                },
                onProgress = { currentSeconds, durationSeconds ->
                    // Persist throttled (the JS reports every ~5 s; don't burn
                    // disk writes faster than the report cadence). Final
                    // positions on pause/end pass the throttle: fraction 1.0
                    // and near-completion values are worth saving immediately.
                    if (durationSeconds > 0) {
                        val fraction = (currentSeconds / durationSeconds)
                            .toFloat().coerceIn(0f, 1f)
                        val now = System.currentTimeMillis()
                        if (fraction >= 0.98f || now - lastProgressSavedAt >= 5_000L) {
                            progressStore.set(video.videoId, fraction)
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
                },
                onAutoplayBlocked = { autoplayBlocked = true }
            )

            if (isBuffering) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.media_player_buffering),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

            if (showTapToPlay) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = stringResource(R.string.media_player_tap_to_play),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
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

        // ── Details panel: BELOW the video area in portrait — it can never
        // cover the player. Hidden in landscape and in vertical fullscreen
        // (the video fills the screen).
        if (!isLandscape && !fullscreenVertical) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .padding(16.dp)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = video.channelName.ifBlank { video.channelId } +
                        if (video.publishedAtEpochMillis > 0L) {
                            " · " + DateUtils.getRelativeTimeSpanString(
                                video.publishedAtEpochMillis,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS
                            ).toString()
                        } else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

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
