package com.muddassir.clearview.media.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.muddassir.clearview.media.download.AudioDownloader
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.download.DownloadStatus
import com.muddassir.clearview.media.download.StoragePolicy
import com.muddassir.clearview.media.util.formatBytes
import com.muddassir.clearview.media.util.formatDownloadDate
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
 * The Downloads view: storage card on top, then active downloads with live
 * progress, then every finished download with Play / Delete actions.
 * Supports multi-select deletion and opens the Manage-storage / Server
 * settings sheets.
 */
@Composable
fun DownloadsSection(
    modifier: Modifier = Modifier,
    onPlayAudio: (DownloadItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showManage by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    val downloads = AudioDownloads.items.value
    val active = AudioDownloads.active
    val activeIds = active.keys.toList()
    val used = AudioDownloads.storageUsedBytes()
    val limit = AudioDownloads.storageLimit.longValue

    Column(modifier = modifier.fillMaxWidth()) {

        // ── Storage card ───────────────────────────────────────────
        StorageCard(
            used = used,
            limit = limit,
            count = downloads.size,
            onManage = { showManage = true },
            onClearAll = { confirmClearAll = true },
            onServer = { showServer = true }
        )

        Spacer(Modifier.height(8.dp))

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
                    onClick = {
                        AudioDownloads.deleteMany(selectedIds.toList())
                        selectedIds.clear()
                        selectionMode = false
                    },
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

        if (downloads.isEmpty() && activeIds.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No downloads yet.\nTap the download button on any video to save its audio offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (activeIds.isNotEmpty()) {
                    item(key = "active-header") {
                        SectionLabel("Active downloads")
                    }
                    items(activeIds, key = { "active-$it" }) { id ->
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
                                .padding(top = if (activeIds.isNotEmpty()) 12.dp else 0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionLabel(if (selectionMode) "Select downloads" else "Downloads")
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
                            onDelete = { AudioDownloads.delete(item.videoId) }
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

    if (showServer) {
        ServerSettingsSheet(onDismiss = { showServer = false })
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all downloads?") },
            text = { Text("All ${downloads.size} downloaded audio files will be deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    AudioDownloads.clearAll()
                }) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            }
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
 * Storage summary card: colored progress bar, used/limit/available, the auto
 * cleanup rules, and Manage Storage / Clear All actions.
 */
@Composable
private fun StorageCard(
    used: Long,
    limit: Long,
    count: Int,
    onManage: () -> Unit,
    onClearAll: () -> Unit,
    onServer: () -> Unit
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
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Storage",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${formatBytes(used)} / ${formatBytes(limit)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Server settings (gear) — where the Render URL lives.
                IconButton(onClick = onServer, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Audio server settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${formatBytes(available)} available · $count download${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onManage, modifier = Modifier.weight(1f)) {
                    Text("Manage storage")
                }
                TextButton(onClick = onClearAll, modifier = Modifier.weight(1f)) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Auto cleanup",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            CleanupHint("Large downloads expire after 15 days")
            CleanupHint("Oldest downloads removed when storage is full")
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
                        "Preparing… waking the server",
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
                            "Downloading ${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Downloading…",
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

/** One finished download: thumbnail, title, channel, source chip, size · date, Play/Delete. */
@Composable
private fun DownloadRow(
    item: DownloadItem,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { if (selectionMode) onToggleSelect() else onPlay() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        }
        OfflineThumbnail(item = item, modifier = Modifier.size(width = 96.dp, height = 54.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
                    text = "${formatBytes(item.fileSize)} · Downloaded ${formatDownloadDate(item.downloadedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!selectionMode) {
            IconButton(onClick = onPlay) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play offline",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete download",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** RSS / URL source chip on download rows. */
@Composable
private fun SourceChip(source: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Text(
            text = if (source == DownloadItem.SOURCE_URL) "URL" else "RSS",
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

/**
 * The compact download control for feed cards: an animated icon button in the
 * corner of the thumbnail showing the four states, and the entry point for
 * the Download → Preparing → Downloading → Downloaded flow on every card.
 */
@Composable
fun DownloadCardAction(
    status: DownloadStatus?,
    isOffline: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                isOffline -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Play offline",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                status is DownloadStatus.Preparing -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
                status is DownloadStatus.Downloading -> {
                    if (status.progress >= 0f) {
                        CircularProgressIndicator(
                            progress = { status.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        // Unknown total size — animate rather than look stuck at 0.
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    }
                }
                status is DownloadStatus.Error -> Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Retry download",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp)
                )
                else -> Icon(
                    Icons.Filled.FileDownload,
                    contentDescription = "Download audio",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
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

            Spacer(Modifier.height(20.dp))
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
                    onClick = onClearAll,
                    enabled = items.isNotEmpty(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear all")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Large downloads (over 15 MB) are deleted automatically after 15 days.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

/** Server-settings sheet: the Render URL + optional token, with a test button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSettingsSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(AudioDownloads.serverUrl()) }
    var token by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Audio server",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Deploy the backend/ folder on Render and paste its URL here " +
                    "(https://your-app.onrender.com). Free-tier servers wake up in ~30–90 s — " +
                    "the app shows an animated Preparing… state while it boots.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; testResult = null },
                label = { Text("Server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; testResult = null },
                label = { Text("Token (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val (ok, detail) = withContext(Dispatchers.IO) {
                                AudioDownloader.checkHealth(url, token.trim().ifBlank { null })
                            }
                            testOk = ok
                            testResult = detail
                            testing = false
                        }
                    },
                    enabled = !testing && url.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Test connection")
                    }
                }
                Button(
                    onClick = {
                        AudioDownloads.saveServer(url, token.trim().ifBlank { null })
                        onDismiss()
                    },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
            testResult?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testOk) storageColor(0.3f) else MaterialTheme.colorScheme.error
                )
            }
            // Plain-http URLs are blocked by Android's default cleartext policy.
            if (url.isNotBlank() && !url.startsWith("https://")) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Use an https:// address — Android blocks plain http by default.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
