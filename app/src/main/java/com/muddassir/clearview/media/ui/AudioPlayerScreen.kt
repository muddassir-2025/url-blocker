package com.muddassir.clearview.media.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.download.OfflineAudioPlayer
import com.muddassir.clearview.media.util.formatBytes
import com.muddassir.clearview.media.util.formatDownloadDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Podcast-style offline audio player, designed after the Spotify now-playing
 * screen: a blurred album-art backdrop, large rounded artwork, a bold
 * left-aligned title/channel, an elegant seek slider (elapsed left, time
 * remaining right), a clean transport row (‑10s / play‑pause / +10s) and a
 * playback-speed pill (0.5x–2x) pinned to the bottom corner. Plays the
 * locally downloaded file through [OfflineAudioPlayer] — no network, no
 * WebView. Fully functional in airplane mode.
 */
@Composable
fun AudioPlayerScreen(
    item: DownloadItem,
    onExit: () -> Unit = {}
) {
    val context = LocalContext.current
    var fileMissing by remember(item.videoId) { mutableStateOf(false) }

    // Load the local file once for this item. Playback runs through the
    // foreground [AudioPlaybackService], so it keeps playing when the app is
    // backgrounded or the screen is off (media notification attached).
    LaunchedEffect(item.videoId) {
        val file = File(context.cacheDir, "audio/${item.fileName}")
        if (file.exists()) {
            OfflineAudioPlayer.play(context, item)
            AudioDownloads.markPlayed(item.videoId)
        } else {
            fileMissing = true
        }
    }

    val isPlaying = OfflineAudioPlayer.isPlaying.value
    val positionMs = OfflineAudioPlayer.positionMs.longValue
    val durationMs = OfflineAudioPlayer.durationMs.longValue
    val speed = OfflineAudioPlayer.speed.value
    var showSpeedMenu by remember { mutableStateOf(false) }

    if (fileMissing) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "This download is no longer available on the device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // ── Spotify-style blurred album-art backdrop ──
        AudioBackdrop(item = item)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Bias the artwork slightly above the vertical center, leaving the
            // bottom free for the controls.
            Spacer(Modifier.weight(0.6f))

            // ── Album art (local file, works offline) ──
            Box(
                modifier = Modifier
                    .size(252.dp)
                    .shadow(18.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
            ) {
                OfflineThumbnail(item = item, modifier = Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(30.dp))

            // ── Title + channel (left-aligned, Spotify style) ──
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.channelName.ifBlank { "YouTube" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(22.dp))

            // ── Seek slider: elapsed on the left, remaining on the right ──
            if (durationMs > 0L) {
                val positionSeconds = (positionMs / 1000f)
                val durationSeconds = (durationMs / 1000f)
                var dragFraction by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragFraction ?: (positionSeconds / durationSeconds).coerceIn(0f, 1f),
                    onValueChange = { dragFraction = it },
                    onValueChangeFinished = {
                        dragFraction?.let { OfflineAudioPlayer.seekTo((it * durationMs).toLong()) }
                        dragFraction = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                    )
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatClock(positionMs / 1000L),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "-${formatClock(((durationMs - positionMs) / 1000L).coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── Transport controls ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { OfflineAudioPlayer.seekTo((positionMs - 10_000L).coerceAtLeast(0L)) },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        Icons.Filled.Replay10,
                        contentDescription = "Back 10 seconds",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(26.dp))
                Surface(
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(10.dp, CircleShape)
                        .clip(CircleShape),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    IconButton(onClick = { OfflineAudioPlayer.toggle() }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(Modifier.width(26.dp))
                IconButton(
                    onClick = { OfflineAudioPlayer.seekTo(positionMs + 10_000L) },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        Icons.Filled.Forward10,
                        contentDescription = "Forward 10 seconds",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Bottom row: download info (left) · speed pill (right) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Downloaded ${formatDownloadDate(item.downloadedAt)} · ${formatBytes(item.fileSize)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    Surface(
                        onClick = { showSpeedMenu = true },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                Icons.Filled.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = formatRate(speed.toDouble()),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false }
                    ) {
                        SPEED_OPTIONS.forEach { rate ->
                            DropdownMenuItem(
                                text = { Text(formatRate(rate)) },
                                trailingIcon = if (rate == speed.toDouble()) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    OfflineAudioPlayer.setSpeed(context, rate.toFloat())
                                    showSpeedMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * Full-bleed blurred backdrop made from the track's own artwork (Spotify
 * style): the local thumbnail is decoded once, scaled up and heavily blurred
 * behind the player, with a theme-colored scrim on top so the foreground text
 * stays readable in both light and dark mode. Falls back to a brand-tinted
 * gradient while the artwork loads (or when there is none).
 */
@Composable
private fun AudioBackdrop(item: DownloadItem) {
    val context = LocalContext.current
    var bitmap by remember(item.thumbnailPath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.thumbnailPath) {
        bitmap = withContext(Dispatchers.IO) {
            if (item.thumbnailPath.isBlank()) null
            else runCatching {
                val f = File(context.cacheDir, "thumbnails/${item.thumbnailPath}")
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            }.getOrNull()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.35f) // keep the blur's soft edges off-screen
                    .blur(40.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            )
        }
        // Scrim: blend the artwork toward the theme surface so text keeps its
        // contrast whether the art is bright or dark.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        )
                    )
                )
        )
    }
}

/** "12:34", or "1:02:34" past an hour. */
private fun formatClock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
