package com.muddassir.clearview.media.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.R
import com.muddassir.clearview.media.data.LiveStreamCacheStore
import com.muddassir.clearview.media.data.LiveStreamConfig
import com.muddassir.clearview.media.data.LiveStreamResolver
import kotlinx.coroutines.delay

/**
 * Live tab: Makkah and Madinah live broadcasts played INSIDE the app.
 *
 * Each official channel's CURRENT live broadcast video id is discovered at
 * runtime (`LiveStreamResolver`), validated, and played with the same in-app
 * IFrame player as regular videos — no HLS/CDN, no YouTube app, no browser.
 * Only ONE stream is loaded at a time.
 *
 * STATES: the tab drives an explicit state machine — RESOLVING (spinner,
 * "Connecting…"), RETRYING (auto-retry with exponential backoff after a
 * transient failure, "temporarily unavailable. Retrying…"), PLAYING, and
 * TEMPORARILY_UNAVAILABLE (manual Retry). When the resolver detects the
 * Restricted-Mode signature a network DNS filter (e.g. CleanBrowsing Family
 * Filter) produces, the tab explains the cause instead of a generic error —
 * retrying cannot help while the filter keeps mapping www.youtube.com to
 * Restricted Mode.
 *
 * LAYOUT (no-overlap guarantee): the `🕋 Makkah Live` / `🕌 Madinah Live`
 * selector is its OWN horizontally scrollable row ABOVE the player — it never
 * overlays the video, so it can never collide with YouTube's native player
 * icons (a real problem on small screens when the chips floated over the
 * video). The selector row collapses to zero height in landscape (immersive
 * fullscreen) but STAYS in composition so the player keeps its slot and
 * rotation never restarts the stream. In portrait the player is a PROPER 16:9
 * card (never stretched to fill the leftover height); in landscape it fills
 * the whole screen. The caption sits BELOW the player in portrait, with any
 * remaining space underneath. The player itself is a NORMAL, un-cropped
 * YouTube embed (no CSS zoom/crop, native controls on tap).
 *
 * QUALITY: the stream is pinned to the best available playback quality
 * (maxQuality) — see the JS quality watchdog in youtube_player.html.
 *
 * CONNECTING BACKDROP: while a stream is connecting — or when it ends / is
 * unavailable — a smooth gradient backdrop ([LiveBackdrop]) fills the player
 * slot (it only ever plays a resolved video id), so the tab never reads as a
 * dead black screen while the player spins up.
 */
@Composable
fun LiveTab(
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val streams = LiveStreamConfig.streams

    var selectedId by rememberSaveable { mutableStateOf(streams.first().id) }
    var videoId by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<LiveState>(LiveState.Resolving) }
    var playerState by remember { mutableStateOf(YtState.UNSTARTED) }
    var retryToken by remember { mutableStateOf(0) }
    // Bumped on every auto-retry so YoutubePlayer re-applies the source even
    // when the resolver returns the SAME id (applySource is deduped on
    // appliedVideoId — without this, a retry would never re-attempt playback).
    var playerRetryToken by remember { mutableStateOf(0) }
    val cacheStore = remember { LiveStreamCacheStore(context.applicationContext) }
    val stream = streams.find { it.id == selectedId } ?: streams.first()

    // Resolution loop: discover → validate → play. Transient failures retry
    // automatically with exponential backoff (5 s → 10 s → 20 s → 40 s),
    // BOUNDED, so YouTube is never hammered. A DNS-filter block (Restricted
    // Mode) stops retrying and explains itself — retrying cannot help while
    // the filter maps www.youtube.com to Restricted Mode.
    // Backup for stream: when primary fails try backup source if available
    val backupStream = remember(selectedId) {
        LiveStreamConfig.backupStreams[selectedId]
    }
    LaunchedEffect(selectedId, retryToken) {
        playerRetryToken = 0
        playerState = YtState.UNSTARTED
        videoId = null
        var attempt = 0
        while (true) {
            state = if (attempt == 0) LiveState.Resolving else LiveState.Retrying
            if (attempt > 0) playerRetryToken++ // force re-apply, same id or not
            var result = LiveStreamResolver.resolveLiveVideoId(
                source = stream,
                cacheStore = cacheStore,
                forceRefresh = attempt > 0
            )
            // Graceful fallback: if Makkah primary unavailable try backup
            if (result.videoId == null && !result.blockedByFilter && backupStream != null) {
                val fallback = LiveStreamResolver.resolveLiveVideoId(backupStream, cacheStore, forceRefresh = attempt > 0)
                if (fallback.videoId != null) result = fallback
            }
            if (result.videoId != null) {
                videoId = result.videoId
                // Wait for the embed to actually start playing; surface the
                // unavailable card instead of an eternal spinner if it never
                // does — and if it never starts, fall through to a retry with
                // a fresh resolution (a new broadcast may have started).
                val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    delay(500)
                    if (playerState == YtState.PLAYING ||
                        playerState == YtState.PAUSED ||
                        state is LiveState.Unavailable
                    ) {
                        break
                    }
                }
                if (playerState == YtState.PLAYING || playerState == YtState.PAUSED) {
                    state = LiveState.Playing
                    break
                }
                videoId = null // never started — retry with a fresh resolution
            } else if (result.blockedByFilter) {
                state = LiveState.Unavailable(blockedByFilter = true)
                break // DNS filter: retrying won't help until YouTube is exempted
            }
            attempt++
            if (attempt > MAX_AUTO_ATTEMPTS) {
                state = LiveState.Unavailable(blockedByFilter = false)
                break
            }
            delay(RETRY_BASE_MS * (1L shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT)))
        }
    }

    val connecting = state is LiveState.Resolving || state is LiveState.Retrying
    val isLive = state is LiveState.Playing
    val unavailable = state as? LiveState.Unavailable

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // ── Channel selector: its OWN row, above the player, so the chips can
        // never overlap the player's mute/CC/settings icons. Horizontally
        // scrollable when the chips don't fit (small screens / large fonts).
        // In landscape it collapses to zero height (fullscreen video) but the
        // row STAYS composed at slot #1, keeping the player's slot #2 stable
        // across rotation — the WebView is never recreated.
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isLandscape) 0.dp else 52.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            items(streams, key = { it.id }) { s ->
                FilterChip(
                    selected = s.id == selectedId,
                    onClick = {
                        if (s.id != selectedId) {
                            selectedId = s.id
                        }
                    },
                    label = { Text(s.title) }
                )
            }
        }

        // ── Player + its overlays: single stable slot, never recreated on
        // rotation. The live broadcast video id is applied when resolution
        // succeeds; the WebView stays composed across channel switches so the
        // player is never torn down and recreated (loadVideoById switches the
        // source in place). While it's connecting (or the stream is
        // unavailable/ended) a frosted blurred backdrop fills the slot so it
        // never reads as a plain black screen.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isLandscape) Modifier.weight(1f)
                    else Modifier.aspectRatio(16f / 9f)
                )
        ) {
            // Normal YouTube embed: no CSS crop, no click shield, the native
            // controls (tap the video for play/pause + mute) stay available.
            YoutubePlayer(
                videoId = videoId,
                modifier = Modifier.fillMaxSize(),
                maxQuality = true,
                retryToken = playerRetryToken,
                onPlayerState = { s ->
                    playerState = s
                    if (s == YtState.PLAYING) state = LiveState.Playing
                    // The live broadcast ended — surface the unavailable card
                    // instead of a frozen "Live · playing" caption.
                    if (s == YtState.ENDED) {
                        Log.w(TAG, "LIVE_STREAM_ENDED channelId=${stream.channelId}")
                        state = LiveState.Unavailable(blockedByFilter = false)
                    }
                },
                onPlayerError = { code ->
                    Log.w(TAG, "LIVE_EMBED_ERROR code=$code channelId=${stream.channelId}")
                    state = LiveState.Unavailable(blockedByFilter = false)
                }
            )

            // Smooth gradient backdrop while connecting (or when the stream is
            // unavailable/ended): hides the idle player slot behind a soft
            // single-layer gradient instead of a plain black screen.
            if (connecting || unavailable != null) {
                LiveBackdrop(modifier = Modifier.fillMaxSize())
            }

            if (connecting) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            if (state is LiveState.Retrying) R.string.live_tab_retrying
                            else R.string.live_tab_connecting
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (unavailable != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = stringResource(
                                if (unavailable.blockedByFilter) R.string.live_tab_filtered
                                else R.string.live_tab_error
                            ),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { retryToken++ }) {
                        Text(stringResource(R.string.media_player_retry))
                    }
                }
            }

        }

        // ── Caption (portrait only): BELOW the player — can never cover it.
        // The 16:9 player leaves leftover height; a weight spacer pins the
        // caption right under the video and lets the empty space sit below.
        if (!isLandscape) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stream.subtitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Live broadcast stream",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFD93025).copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFFD93025), CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "LIVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD93025)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Live tab state machine: RESOLVING (first attempt), RETRYING (auto-retry
 * after a transient failure, with backoff), PLAYING, and TEMPORARILY_
 * UNAVAILABLE. [Unavailable.blockedByFilter] distinguishes a network DNS
 * filter (Restricted Mode) block — which retrying cannot fix — from a plain
 * "no broadcast / can't reach" state.
 */
private sealed interface LiveState {
    object Resolving : LiveState
    object Retrying : LiveState
    object Playing : LiveState
    data class Unavailable(val blockedByFilter: Boolean) : LiveState
}

/**
 * Connecting backdrop for the Live tab: a SINGLE smooth full-area gradient
 * (theme container colors, plus a soft top glow), under a light scrim for
 * legibility. Shown while a stream is connecting (or when it ends / is
 * unavailable) so the player slot never reads as a dead black screen. Purely
 * decorative — the spinner and error card are drawn on top of it by the
 * caller.
 *
 * IMPORTANT: drawn with gradient brushes, NOT `Modifier.blur`. Compose's blur
 * effect silently falls back to UNBLURRED rendering on some GPUs, which made
 * the earlier multi-blob design appear as a mosaic of hard-edged solid
 * rectangles. Linear gradients are rendered natively on every device — they
 * always look smooth and can never fragment or tile.
 */
@Composable
private fun LiveBackdrop(modifier: Modifier = Modifier) {
    // Base: one continuous vertical wash across the palette container colors.
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.tertiaryContainer
                )
            )
        )
    ) {
        // Soft top glow — still a single linear gradient layer, no blur.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )
                )
        )
        // Scrim so the spinner / labels stay legible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
        )
    }
}

private const val TAG = "LiveTab"

/** If a resolved stream never starts within this window, retry / surface the card. */
private const val LOAD_TIMEOUT_MS = 25_000L

/** Automatic resolution retries (after the first attempt), then manual Retry. */
private const val MAX_AUTO_ATTEMPTS = 4

/** Exponential backoff base (5 s → 10 s → 20 s → 40 s, capped by [MAX_BACKOFF_SHIFT]). */
private const val RETRY_BASE_MS = 5_000L
private const val MAX_BACKOFF_SHIFT = 3
