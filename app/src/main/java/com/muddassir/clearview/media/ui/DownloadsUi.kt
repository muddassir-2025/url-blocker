package com.muddassir.clearview.media.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.media.data.UserPlaylistStore
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.download.DownloadStatus
import com.muddassir.clearview.media.download.OfflineAudioPlayer
import com.muddassir.clearview.media.download.StoragePolicy
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.util.formatBytes
import com.muddassir.clearview.media.util.formatDownloadDate
import com.muddassir.clearview.media.util.formatEtaRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Storage colors: green 0–70%, yellow 70–90%, red 90–100% (of the limit). */
fun storageColor(fraction: Float): Color = when {
    fraction < 0.7f -> Color(0xFF2E7D32)
    fraction < 0.9f -> Color(0xFFF9A825)
    else -> Color(0xFFD32F2F)
}

/**
 * The Downloads view: storage card + settings gear, a live search over the
 * offline audios, then active downloads with progress (incl. time remaining)
 * and every finished download with Play / ⋯ actions. When [channelId] is set
 * (a channel selected in the strip above), only that channel's downloads are
 * shown. Supports multi-select deletion and opens the Manage-storage sheet
 * from the gear / Manage-storage button.
 */
@Composable
fun DownloadsSection(
    modifier: Modifier = Modifier,
    /** Restricts the list to one channel's downloads (null = all channels). */
    channelId: String? = null,
    /** The selected channel's display name — fallback for downloads saved
     *  before [DownloadItem.channelId] existed (they carry no id but their
     *  channel name still matches). */
    channelName: String? = null,
    /** Live search text (title / channel name match). */
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onPlayAudio: (DownloadItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showManage by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    // A download awaiting "delete offline audio" confirmation (single row).
    var pendingDeleteItem by remember { mutableStateOf<DownloadItem?>(null) }
    // Importing audio files picked from the device (multi-select SAF picker).
    var importing by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        AudioDownloads.importFromDevice(
            context = context,
            uris = uris,
            channelId = channelId.orEmpty(),
            channelName = channelName.orEmpty()
        ) { imported ->
            importing = false
            Toast.makeText(
                context,
                if (imported == uris.size) "Imported $imported audio${if (imported == 1) "" else "s"}"
                else "Imported $imported of ${uris.size} audios",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val addAudio: () -> Unit = { importLauncher.launch(arrayOf("audio/*")) }
    // Multi-select deletion confirmation.
    var pendingMultiDelete by remember { mutableStateOf(false) }
    // A downloaded item awaiting the add-to-playlist picker / name dialog.
    var pendingPlaylistItem by remember { mutableStateOf<DownloadItem?>(null) }
    var pendingCreatePlaylistItem by remember { mutableStateOf<DownloadItem?>(null) }
    val userPlaylistStore = remember { UserPlaylistStore(context.applicationContext) }
    var playlistRevision by remember { mutableIntStateOf(0) }
    val playlists = remember(playlistRevision) { userPlaylistStore.getPlaylists() }

    val allDownloads = AudioDownloads.items.value
    val active = AudioDownloads.active
    val activeIds = active.keys.toList()
    val used = AudioDownloads.storageUsedBytes()
    val limit = AudioDownloads.storageLimit.longValue

    // ── Scoping: the selected channel's downloads first, then the live
    // search on top (title / channel name, case-insensitive). Older downloads
    // (empty channelId) still match their channel via the name fallback. ──
    val channelDownloads = remember(allDownloads, channelId, channelName) {
        if (channelId == null) allDownloads
        else allDownloads.filter { item ->
            item.channelId == channelId ||
                (item.channelId.isBlank() && channelName != null &&
                    item.channelName.equals(channelName, ignoreCase = true))
        }
    }
    val channelActiveIds = remember(activeIds, channelId) {
        if (channelId == null) activeIds
        else activeIds.filter { id ->
            AudioDownloads.pendingVideos[id]?.channelId == channelId
        }
    }
    val q = searchQuery.trim()
    val downloads = remember(channelDownloads, q) {
        if (q.isEmpty()) channelDownloads
        else channelDownloads.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.channelName.contains(q, ignoreCase = true)
        }
    }
    val visibleActiveIds = remember(channelActiveIds, q) {
        if (q.isEmpty()) channelActiveIds
        else channelActiveIds.filter { id ->
            val v = AudioDownloads.pendingVideos[id]
            v?.title?.contains(q, ignoreCase = true) == true ||
                v?.channelName?.contains(q, ignoreCase = true) == true
        }
    }
    val hasAnything = allDownloads.isNotEmpty() || activeIds.isNotEmpty()

    Column(modifier = modifier.fillMaxWidth()) {

        // ── Storage card: just the bar + gear. All stats, the limit picker
        // and the auto-cleanup rules live in the Manage-storage sheet.
        StorageCard(
            used = used,
            limit = limit,
            onManage = { showManage = true },
            onAddAudio = addAudio,
            importing = importing
        )

        // ── Search (offline audios) ────────────────────────────────
        if (hasAnything) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search downloads") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }

        Spacer(Modifier.height(4.dp))

        // ── Selection toolbar (multi-select delete) ────────────────
        AnimatedVisibility(visible = selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedIds.size} selected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    if (selectedIds.size == downloads.size) selectedIds.clear()
                    else selectedIds.clear().let {
                        downloads.forEach { selectedIds.add(it.videoId) }
                    }
                }) {
                    Text(if (selectedIds.size == downloads.size) "Clear" else "Select all")
                }
                Button(
                    onClick = { pendingMultiDelete = true },
                    enabled = selectedIds.isNotEmpty(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }

        when {
            // Nothing downloaded anywhere (incl. other channels).
            !hasAnything -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No downloads yet.\nAdd audio from your device, or open any video's ⋮ menu and tap \"Download audio\" to save it offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = addAudio,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Importing…")
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add audio from device")
                    }
                }
            }
            // Downloads exist, but the channel scope / search hides them all.
            downloads.isEmpty() && visibleActiveIds.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        q.isNotEmpty() -> "No downloads match your search."
                        channelId != null -> "No downloaded audios for this channel yet."
                        else -> "No downloads yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (visibleActiveIds.isNotEmpty()) {
                    item(key = "active-header") {
                        SectionLabel("Active downloads")
                    }
                    items(visibleActiveIds, key = { "active-$it" }) { id ->
                        val status = active[id]
                        if (status != null) {
                            ActiveDownloadRow(
                                videoId = id,
                                status = status,
                                onCancel = { AudioDownloads.cancel(id) }
                            )
                        }
                    }
                }
                if (downloads.isNotEmpty()) {
                    item(key = "downloads-header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (visibleActiveIds.isNotEmpty()) 12.dp else 0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionLabel(
                                if (selectionMode) "Select downloads"
                                else if (downloads.size == allDownloads.size) "Downloads"
                                else "${downloads.size} downloads"
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { selectionMode = !selectionMode }) {
                                Text(if (selectionMode) "Done" else "Select")
                            }
                        }
                    }
                    items(downloads, key = { it.videoId }) { item ->
                        DownloadRow(
                            item = item,
                            selectionMode = selectionMode,
                            selected = item.videoId in selectedIds,
                            onToggleSelect = {
                                if (item.videoId in selectedIds) selectedIds.remove(item.videoId)
                                else selectedIds.add(item.videoId)
                            },
                            onPlay = { onPlayAudio(item) },
                            onAddToPlaylist = { pendingPlaylistItem = item },
                            onDelete = { pendingDeleteItem = item }
                        )
                    }
                }
            }
        }
    }

    if (showManage) {
        ManageStorageSheet(
            items = downloads,
            used = used,
            limit = limit,
            onSetLimit = { AudioDownloads.setStorageLimit(it) },
            onClearExpired = {
                scope.launch { withContext(Dispatchers.IO) {
                    val store = com.muddassir.clearview.media.download.AudioDownloadStore(context.applicationContext)
                    store.deleteExpired(System.currentTimeMillis())
                } }
                AudioDownloads.refresh()
            },
            onClearAll = { AudioDownloads.clearAll(); showManage = false },
            onDismiss = { showManage = false }
        )
    }

    // ── Delete offline audio confirmation (single row) ─────────────
    pendingDeleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteItem = null },
            title = { Text("Delete download?") },
            text = { Text("Delete the offline audio of \"${item.title}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    AudioDownloads.delete(item.videoId)
                    pendingDeleteItem = null
                    Toast.makeText(context, "Download deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteItem = null }) { Text("Cancel") }
            }
        )
    }

    // ── Delete confirmation (multi-select) ─────────────────────────
    if (pendingMultiDelete) {
        AlertDialog(
            onDismissRequest = { pendingMultiDelete = false },
            title = { Text("Delete ${selectedIds.size} downloads?") },
            text = { Text("The selected audio files will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    AudioDownloads.deleteMany(selectedIds.toList())
                    selectedIds.clear()
                    selectionMode = false
                    pendingMultiDelete = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMultiDelete = false }) { Text("Cancel") }
            }
        )
    }

    // ── Add to playlist (downloaded audio) ─────────────────────────
    pendingPlaylistItem?.let { item ->
        val media = item.toMediaVideo()
        AddToPlaylistSheet(
            video = media,
            playlists = playlists,
            onAdd = { p ->
                userPlaylistStore.addVideos(p.id, listOf(media))
                playlistRevision++
                pendingPlaylistItem = null
                Toast.makeText(context, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
            },
            onCreateNew = {
                pendingPlaylistItem = null
                pendingCreatePlaylistItem = item
            },
            onDismiss = { pendingPlaylistItem = null }
        )
    }
    pendingCreatePlaylistItem?.let { item ->
        PlaylistNameDialog(
            initial = "",
            title = "New playlist",
            confirmLabel = "Create",
            onSubmit = { name ->
                userPlaylistStore.createPlaylist(name, listOf(item.toMediaVideo()))
                pendingCreatePlaylistItem = null
                Toast.makeText(context, "Created \"$name\"", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { pendingCreatePlaylistItem = null }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * Storage summary card — deliberately slim: the gear, "X used · Y
 * remaining" and the colored progress bar. Every other stat (count, limit,
 * largest/oldest files, expiring items) plus the auto-cleanup rules and the
 * destructive Clear actions live inside the Manage-storage sheet, so the
 * Downloads list starts with a compact bar instead of a tall card.
 */
@Composable
private fun StorageCard(
    used: Long,
    limit: Long,
    onManage: () -> Unit,
    /** When set, an "Add audio" button appears next to the gear. */
    onAddAudio: (() -> Unit)? = null,
    importing: Boolean = false
) {
    val fraction = StoragePolicy.usageFraction(used, limit)
    val barColor = storageColor(fraction)
    val available = (limit - used).coerceAtLeast(0L)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Storage",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // Import audio files from the device (multi-select picker).
                if (onAddAudio != null) {
                    IconButton(
                        onClick = onAddAudio,
                        enabled = !importing,
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add audio from device",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                // Media settings gear: opens the sheet with all the storage
                // stats, the limit picker and the cleanup actions.
                IconButton(onClick = onManage, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Media settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // Used / remaining at a glance (the core of the storage card).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${formatBytes(used)} used",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "· ${formatBytes(available)} remaining",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun CleanupHint(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A download in progress: thumbnail, animated status line, progress bar, cancel. */
@Composable
private fun ActiveDownloadRow(
    videoId: String,
    status: DownloadStatus,
    onCancel: () -> Unit
) {
    val video = AudioDownloads.pendingVideos[videoId]
    val isDownloading = status is DownloadStatus.Downloading
    val progress = (status as? DownloadStatus.Downloading)?.progress

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 54.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            if (video != null) {
                RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video?.title ?: "Preparing audio…",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            when (status) {
                is DownloadStatus.Preparing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Preparing audio…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is DownloadStatus.Downloading -> {
                    if (progress != null && progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Downloading ${(progress.coerceIn(0f, 1f) * 100).toInt()}%" +
                                etaSuffix(status.etaSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Downloading…" + etaSuffix(status.etaSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is DownloadStatus.Error -> Text(
                    status.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cancel download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One finished download, Spotify-style: square album art with a play badge
 * (or a live animated equalizer while this track is the one playing), title /
 * channel, duration · size, and a single ⋯ menu (Play/Pause, Add to playlist,
 * Delete download). The row highlights subtly while its audio is loaded.
 */
@Composable
private fun DownloadRow(
    item: DownloadItem,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit
) {
    val isCurrent = OfflineAudioPlayer.playingVideoId.value == item.videoId
    val isPlaying = isCurrent && OfflineAudioPlayer.isPlaying.value
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                else Color.Transparent
            )
            .clickable { if (selectionMode) onToggleSelect() else onPlay() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        }

        // ── Square album art with play badge / live equalizer ──
        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) {
            OfflineThumbnail(item = item, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    NowPlayingBars(modifier = Modifier.size(18.dp))
                } else {
                    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.45f)) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play offline",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp).padding(4.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.channelName.ifBlank { "YouTube" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceChip(source = item.source)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = buildString {
                        if (item.durationSeconds > 0L) {
                            append(formatTrackTime(item.durationSeconds))
                            append(" · ")
                        }
                        append(formatBytes(item.fileSize))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!selectionMode) {
            // ── ⋯ menu: Play/Pause (toggle on the loaded track), Add to
            // playlist, Delete download. Replaces the old three-icon row. ──
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                when {
                                    isPlaying -> "Pause"
                                    isCurrent -> "Resume"
                                    else -> "Play"
                                }
                            )
                        },
                        onClick = {
                            menuOpen = false
                            if (isCurrent) OfflineAudioPlayer.toggle() else onPlay()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to playlist") },
                        onClick = {
                            menuOpen = false
                            onAddToPlaylist()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete download") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Three animated equalizer bars over the album art of the currently playing
 * track (Spotify-style "now playing" cue). Phase-offset so the bars feel
 * organic; the animation runs only while this track is actually playing.
 */
@Composable
private fun NowPlayingBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eq")
    val h1 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "eq1"
    )
    val h2 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(640, delayMillis = 120), RepeatMode.Reverse),
        label = "eq2"
    )
    val h3 by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(460, delayMillis = 60), RepeatMode.Reverse),
        label = "eq3"
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EqBar(fraction = h1)
        EqBar(fraction = h2)
        EqBar(fraction = h3)
    }
}

@Composable
private fun EqBar(fraction: Float) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(14.dp * fraction.coerceIn(0f, 1f))
            .clip(CircleShape)
            .background(Color.White)
    )
}

/** " · ~2m 30s left" suffix for a downloading line (or "" while unknown). */
private fun etaSuffix(seconds: Long): String {
    val eta = formatEtaRemaining(seconds)
    return if (eta.isEmpty()) "" else " · $eta"
}

/** "12:34" (or "1:02:34" past an hour) for a track duration in seconds. */
private fun formatTrackTime(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** RSS / URL / Device source chip on download rows. */
@Composable
private fun SourceChip(source: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Text(
            text = when (source) {
                DownloadItem.SOURCE_URL -> "URL"
                DownloadItem.SOURCE_DEVICE -> "Device"
                else -> "RSS"
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/** Local thumbnail for a download (works offline); initials-style fallback. */
@Composable
fun OfflineThumbnail(item: DownloadItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(item.thumbnailPath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.thumbnailPath) {
        bitmap = withContext(Dispatchers.IO) {
            if (item.thumbnailPath.isBlank()) null
            else {
                val f = File(context.cacheDir, "thumbnails/${item.thumbnailPath}")
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            }
        }
    }

    Box(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Manage-storage sheet: stats + storage limit choices + Clear Expired / Clear All. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageStorageSheet(
    items: List<DownloadItem>,
    used: Long,
    limit: Long,
    onSetLimit: (Long) -> Unit,
    onClearExpired: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmClearAll by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val largest = items.maxByOrNull { it.fileSize }
    val oldest = items.minByOrNull { it.downloadedAt }
    val expiringSoon = StoragePolicy.expiringSoonItems(items, now)
    val expiredCount = StoragePolicy.expiredItems(items, now).size
    val limitOptions = listOf(
        500L * 1024 * 1024,
        1024L * 1024 * 1024,
        2L * 1024 * 1024 * 1024,
        5L * 1024 * 1024 * 1024
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Manage storage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(14.dp))
            StatRow("Storage used", formatBytes(used))
            StatRow("Storage limit", formatBytes(limit))
            StatRow("Available", formatBytes((limit - used).coerceAtLeast(0L)))
            StatRow("Downloads", items.size.toString())
            StatRow("Total size", formatBytes(used))
            StatRow("Largest download", largest?.let { formatBytes(it.fileSize) } ?: "—")
            StatRow("Oldest download", oldest?.let { formatDownloadDate(it.downloadedAt) } ?: "—")
            StatRow(
                "Files expiring soon",
                if (expiringSoon.isEmpty()) "None" else expiringSoon.size.toString()
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Storage limit",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                limitOptions.forEach { option ->
                    FilterChip(
                        selected = limit == option,
                        onClick = { onSetLimit(option) },
                        label = { Text(formatBytes(option)) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            // Auto-cleanup rules (moved here from the storage card so the
            // Downloads view keeps just the compact bar).
            Text(
                text = "Auto cleanup",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            CleanupHint("Large downloads (over 15 MB) expire after 15 days")
            CleanupHint("Oldest downloads removed when storage is full")

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onClearExpired,
                    enabled = expiredCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (expiredCount > 0) "Clear expired ($expiredCount)" else "Clear expired")
                }
                Button(
                    onClick = { confirmClearAll = true },
                    enabled = items.isNotEmpty(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear all")
                }
            }
        }
    }

    // Clear all lives here (not on the storage card) and asks first, so an
    // accidental tap can never wipe the library.
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all downloads?") },
            text = { Text("All ${items.size} downloaded audio files will be deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    onClearAll()
                }) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** The feed-style video a download belongs to (playlist add etc.). */
private fun DownloadItem.toMediaVideo(): MediaVideo = MediaVideo(
    videoId = videoId,
    title = title,
    channelId = "",
    channelName = channelName.ifBlank { "YouTube" },
    publishedAtEpochMillis = downloadedAt,
    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
    durationSeconds = durationSeconds
)


