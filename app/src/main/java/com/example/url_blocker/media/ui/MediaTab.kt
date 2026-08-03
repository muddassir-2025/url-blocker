package com.example.url_blocker.media.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.url_blocker.media.data.MediaRepository
import com.example.url_blocker.media.model.MediaVideo
import com.example.url_blocker.media.model.SavedChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Media tab: saved channels (add/remove/select) plus the latest videos of the
 * selected channel. Videos load instantly from the local cache, then refresh
 * in the background; every failure falls back to cached data with a hint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTab(
    onPlayVideo: (MediaVideo) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { MediaRepository(context.applicationContext) }

    var channels by remember { mutableStateOf(repository.getSavedChannels()) }
    var selectedChannelId by remember {
        mutableStateOf(
            repository.getSelectedChannelId()
                ?: repository.getSavedChannels().firstOrNull()?.channelId
        )
    }
    var videos by remember { mutableStateOf<List<MediaVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showingCached by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<SavedChannel?>(null) }

    // Load on first open and whenever the selected channel changes: cached
    // data first (instant), then a background refresh that swaps in fresh
    // videos when it arrives.
    LaunchedEffect(selectedChannelId) {
        val channelId = selectedChannelId ?: return@LaunchedEffect
        isLoading = true
        errorMessage = null
        val cached = withContext(Dispatchers.IO) { repository.getCachedVideos(channelId) }
        if (cached != null) {
            videos = cached.first
            showingCached = true
        }
        val fresh = repository.refreshVideos(channelId)
        if (fresh != null) {
            videos = fresh
            showingCached = false
        } else if (cached == null) {
            errorMessage = "Couldn't load videos. Check your internet connection."
        }
        isLoading = false
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Saved channels ───────────────────────────────────────────
        Text(
            text = "Saved Channels",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(channels, key = { it.channelId }) { channel ->
                FilterChip(
                    selected = channel.channelId == selectedChannelId,
                    onClick = {
                        selectedChannelId = channel.channelId
                        repository.setSelectedChannel(channel.channelId)
                    },
                    label = { Text(channel.displayName, maxLines = 1) },
                    trailingIcon = {
                        Box(Modifier.clickable { pendingRemove = channel }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove ${channel.displayName}",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                )
            }
            item {
                AssistChip(
                    onClick = { showAddDialog = true },
                    label = { Text("Add") },
                    leadingIcon = {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Latest videos ────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Latest Videos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            if (showingCached && !isLoading) {
                Text(
                    text = "cached",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            errorMessage != null && videos.isEmpty() -> ErrorCard(errorMessage!!)
            channels.isEmpty() && videos.isEmpty() && !isLoading ->
                ErrorCard("No channels saved. Tap + Add to save one.")
            videos.isEmpty() && !isLoading -> ErrorCard("No videos yet for this channel.")
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(videos, key = { it.videoId }) { video ->
                        VideoCard(video = video, onClick = { onPlayVideo(video) })
                    }
                }
            }
        }
    }

    // ── Add channel dialog ──────────────────────────────────────────
    if (showAddDialog) {
        AddChannelDialog(
            repository = repository,
            onAdded = { channel ->
                showAddDialog = false
                channels = repository.getSavedChannels()
                selectedChannelId = channel.channelId
                repository.setSelectedChannel(channel.channelId)
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // ── Remove channel confirmation ─────────────────────────────────
    pendingRemove?.let { channel ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove channel?") },
            text = { Text("Remove \"${channel.displayName}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.removeChannel(channel.channelId)
                    channels = repository.getSavedChannels()
                    if (selectedChannelId == channel.channelId) {
                        selectedChannelId = channels.firstOrNull()?.channelId
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoCard(video: MediaVideo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column {
            RemoteImage(
                url = video.thumbnailUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = video.channelName.ifBlank { "YouTube" } +
                        if (video.publishedAtEpochMillis > 0L) {
                            " · " + DateUtils.getRelativeTimeSpanString(
                                video.publishedAtEpochMillis,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS
                            ).toString()
                        } else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
