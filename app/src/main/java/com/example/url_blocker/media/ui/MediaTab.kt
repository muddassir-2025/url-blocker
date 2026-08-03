package com.example.url_blocker.media.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.url_blocker.media.data.MediaRepository
import com.example.url_blocker.media.data.WatchProgressStore
import com.example.url_blocker.media.model.MediaVideo
import com.example.url_blocker.media.model.SavedChannel
import com.example.url_blocker.media.util.formatViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Media tab — a Subscriptions-style feed:
 *
 *  1. Channel avatar strip on top (tap an avatar to filter to that channel,
 *     tap again or tap "All" to show everything; "+" adds a channel).
 *  2. Shorts section (vertical cards, newest first).
 *  3. Long-video list (YouTube-style rows) — each thumbnail carries a red
 *     watch-progress bar and a "Watched" badge once ≥90% was viewed (progress
 *     comes from the in-app player's real getCurrentTime/getDuration).
 *
 * The feed aggregates EVERY saved channel (cached data first for an instant
 * first paint, then a background refresh of all feeds merged newest-first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTab(
    onPlayVideo: (MediaVideo) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { MediaRepository(context.applicationContext) }
    val progressStore = remember { WatchProgressStore(context.applicationContext) }

    var channels by remember { mutableStateOf(repository.getSavedChannels()) }
    var filterChannelId by remember { mutableStateOf<String?>(null) }
    var videos by remember { mutableStateOf<List<MediaVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showingCached by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<SavedChannel?>(null) }

    // Stable key: the channel IDS only. The avatar backfill below replaces the
    // list object with the same ids (avoids keying the feed load on the list
    // identity — which would re-fetch every feed whenever an avatar landed).
    val channelIdsKey = channels.map { it.channelId }.sorted().joinToString(",")

    // Aggregate load: every channel's cached videos first (instant), then a
    // background refresh of all feeds merged into one list. Re-runs only when
    // a channel is added or removed.
    LaunchedEffect(channelIdsKey) {
        if (channels.isEmpty()) {
            videos = emptyList()
            isLoading = false
            errorMessage = null
            return@LaunchedEffect
        }
        isLoading = true
        errorMessage = null
        val cached = withContext(Dispatchers.IO) { repository.getAllCachedVideos(channels) }
        if (cached.isNotEmpty()) {
            videos = cached
            showingCached = true
        }
        val fresh = repository.refreshAllVideos(channels)
        if (fresh != null) {
            videos = fresh
            showingCached = false
        } else if (cached.isEmpty()) {
            errorMessage = "Couldn't load videos. Check your internet connection."
        }
        isLoading = false
    }

    // Best-effort avatar backfill (separate effect so an avatar landing never
    // re-triggers the feed load). Runs on open and after add/remove; the
    // updated list keeps the same channel ids so the feed effect stays put.
    LaunchedEffect(channelIdsKey) {
        if (channels.isEmpty()) return@LaunchedEffect
        val withAvatars = withContext(Dispatchers.IO) { repository.fillMissingAvatars(channels) }
        if (withAvatars != null) channels = withAvatars
    }

    val displayed = if (filterChannelId == null) {
        videos
    } else {
        videos.filter { it.channelId == filterChannelId }
    }
    val shorts = displayed.filter { it.isShort }
    val longs = displayed.filterNot { it.isShort }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Channel avatar strip (Subscriptions-style) ─────────────
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item(key = "all") {
                AllAvatar(
                    selected = filterChannelId == null,
                    onClick = { filterChannelId = null }
                )
            }
            items(channels, key = { it.channelId }) { channel ->
                ChannelAvatar(
                    channel = channel,
                    selected = filterChannelId == channel.channelId,
                    onClick = {
                        filterChannelId =
                            if (filterChannelId == channel.channelId) null else channel.channelId
                    },
                    onRemove = { pendingRemove = channel }
                )
            }
            item(key = "add") {
                AddAvatar(onClick = { showAddDialog = true })
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Feed ───────────────────────────────────────────────────
        when {
            errorMessage != null && videos.isEmpty() -> ErrorCard(errorMessage!!)
            channels.isEmpty() && videos.isEmpty() && !isLoading ->
                ErrorCard("No channels saved. Tap + Add to save one.")
            videos.isEmpty() && !isLoading -> ErrorCard("No videos yet for your channels.")
            videos.isNotEmpty() && displayed.isEmpty() && !isLoading ->
                ErrorCard("No videos for this channel yet.")
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (shorts.isNotEmpty()) {
                        item(key = "shorts-header") {
                            SectionHeader(
                                title = "Shorts",
                                isLoading = isLoading,
                                showingCached = showingCached
                            )
                        }
                        item(key = "shorts-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(shorts, key = { it.videoId }) { video ->
                                    ShortCard(video = video, onClick = { onPlayVideo(video) })
                                }
                            }
                        }
                    }
                    if (longs.isNotEmpty()) {
                        item(key = "videos-header") {
                            SectionHeader(
                                title = "Videos",
                                isLoading = isLoading,
                                showingCached = showingCached
                            )
                        }
                        items(longs, key = { it.videoId }) { video ->
                            LongVideoCard(
                                video = video,
                                progressStore = progressStore,
                                onClick = { onPlayVideo(video) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Add channel dialog ─────────────────────────────────────────
    if (showAddDialog) {
        AddChannelDialog(
            repository = repository,
            onAdded = { channel ->
                showAddDialog = false
                channels = repository.getSavedChannels()
                filterChannelId = channel.channelId
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // ── Remove channel confirmation ────────────────────────────────
    pendingRemove?.let { channel ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove channel?") },
            text = { Text("Remove \"${channel.displayName}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.removeChannel(channel.channelId)
                    channels = repository.getSavedChannels()
                    if (filterChannelId == channel.channelId) filterChannelId = null
                    pendingRemove = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    isLoading: Boolean,
    showingCached: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Accent bar — the app's brand color, echoing the player's progress accent.
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        if (showingCached && !isLoading) {
            Text(
                text = "cached",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "All" avatar at the head of the strip — shows every channel's feed. */
@Composable
private fun AllAvatar(selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "All",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "All",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A saved channel: circular avatar (or initials) with its name below. The
 * small ✕ badge in the avatar's corner removes the channel (the inner
 * clickable consumes the tap, so it never also triggers the filter).
 */
@Composable
private fun ChannelAvatar(
    channel: SavedChannel,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.size(52.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(
                        width = if (selected) 2.dp else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            ) {
                if (channel.avatarUrl != null) {
                    RemoteImage(
                        url = channel.avatarUrl,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials(channel.displayName),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // Remove badge (✕) in the corner.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove ${channel.displayName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "+" entry at the end of the strip — opens the add-channel dialog. */
@Composable
private fun AddAvatar(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add channel",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Add",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Vertical Shorts card for the horizontal Shorts row — the title and view
 * count are overlaid on the thumbnail over a bottom scrim (YouTube Shorts
 * style), with a small play badge in the corner.
 */
@Composable
private fun ShortCard(video: MediaVideo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(150.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
        ) {
            RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
            // Bottom scrim keeps the overlay text legible on any thumbnail.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
            )
            // Play badge in the corner.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.35f)
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(2.dp).size(16.dp)
                )
            }
            // Title + views overlaid at the bottom.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = video.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (video.viewCount > 0L) {
                        formatViews(video.viewCount) + " views"
                    } else {
                        video.channelName.ifBlank { "YouTube" }
                    },
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * YouTube-style long-video row: thumbnail with a red watch-progress bar and a
 * "Watched" badge, title + channel/date to the right.
 */
@Composable
private fun LongVideoCard(
    video: MediaVideo,
    progressStore: WatchProgressStore,
    onClick: () -> Unit
) {
    val fraction = remember(video.videoId) { progressStore.get(video.videoId) }
    val watched = (fraction ?: 0f) >= 0.9f

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row {
            // ── Thumbnail + progress overlay ──
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .aspectRatio(16f / 9f)
            ) {
                RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
                if (watched) {
                    // Dim + badge, like YouTube's watched treatment.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "Watched",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                } else if (fraction != null && fraction > 0.02f) {
                    // In-progress: YouTube-style thin progress bar.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            // ── Title / channel · views · date ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(10.dp)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(video.channelName.ifBlank { "YouTube" })
                        if (video.viewCount > 0L) {
                            append(" · ").append(formatViews(video.viewCount)).append(" views")
                        }
                        if (video.publishedAtEpochMillis > 0L) {
                            append(" · ").append(
                                DateUtils.getRelativeTimeSpanString(
                                    video.publishedAtEpochMillis,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS
                                ).toString()
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AddChannelDialog(
    repository: MediaRepository,
    onAdded: (SavedChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Add channel") },
        text = {
            Column {
                Text(
                    text = "Paste a channel URL or @handle, e.g. @SafinaSociety",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    singleLine = true,
                    enabled = !adding,
                    isError = error != null,
                    label = { Text("Channel handle or URL") }
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !adding,
                onClick = {
                    adding = true
                    scope.launch {
                        when (val result = repository.addChannel(input)) {
                            is MediaRepository.AddChannelResult.Success -> onAdded(result.channel)
                            is MediaRepository.AddChannelResult.Error -> {
                                error = result.message
                                adding = false
                            }
                        }
                    }
                }
            ) { Text(if (adding) "Adding…" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) { Text("Cancel") }
        }
    )
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📡", fontSize = 36.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Two-letter initials for the avatar fallback ("Safina Society" → "SS"). */
private fun initials(name: String): String {
    val parts = name.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    return parts
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifBlank { name.take(1).uppercase() }
}
