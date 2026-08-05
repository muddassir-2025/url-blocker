package com.muddassir.clearview.media.ui

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
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
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
import java.io.File

/**
 * Podcast-style offline audio player: a large thumbnail, title/channel, a
 * seek slider and transport controls (‑15s / play‑pause / +15s). Plays the
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

    // Load the local file once for this item.
    LaunchedEffect(item.videoId) {
        val file = File(context.cacheDir, "audio/${item.fileName}")
        if (file.exists()) {
            OfflineAudioPlayer.play(context, file, item.videoId)
            AudioDownloads.markPlayed(item.videoId)
        } else {
            fileMissing = true
        }
    }

    val isPlaying = OfflineAudioPlayer.isPlaying.value
    val positionMs = OfflineAudioPlayer.positionMs.longValue
    val durationMs = OfflineAudioPlayer.durationMs.longValue

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))

        // ── Thumbnail (local file, works offline) ──
        Box(
            modifier = Modifier
                .size(230.dp)
                .clip(RoundedCornerShape(24.dp))
        ) {
            OfflineThumbnail(item = item, modifier = Modifier.fillMaxSize())
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.channelName.ifBlank { "YouTube" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.weight(1f))

        // ── Seek slider ──
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
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatClock(positionMs / 1000L),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatClock(durationMs / 1000L),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                text = "Loading…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Transport controls ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { OfflineAudioPlayer.seekTo((positionMs - 15_000L).coerceAtLeast(0L)) },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    Icons.Filled.Replay10,
                    contentDescription = "Back 15 seconds",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Surface(
                modifier = Modifier
                    .size(72.dp)
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
            Spacer(Modifier.width(16.dp))
            IconButton(
                onClick = { OfflineAudioPlayer.seekTo(positionMs + 15_000L) },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    Icons.Filled.Forward10,
                    contentDescription = "Forward 15 seconds",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Downloaded ${formatDownloadDate(item.downloadedAt)} · ${formatBytes(item.fileSize)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
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
