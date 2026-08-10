package com.muddassir.clearview.media.download

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muddassir.clearview.media.model.MediaVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/** Live state of one in-flight (or failed) download. */
sealed class DownloadStatus {
    /** Resolving the audio stream on-device (NewPipeExtractor) before bytes flow. */
    data object Preparing : DownloadStatus()

    /** Streaming; [progress] is 0..1 (or -1 when the total size is unknown),
     *  and [etaSeconds] is the estimated time remaining (or -1 while it's too
     *  early / unknown to estimate). */
    data class Downloading(val progress: Float, val etaSeconds: Long = -1L) : DownloadStatus()

    /** The download failed; [message] is user-facing. Tap again to retry. */
    data class Error(val message: String) : DownloadStatus()
}

/**
 * App-wide coordinator for offline audio downloads.
 *
 * A process-wide singleton so download progress is observable from anywhere
 * (feed cards, the video player, the Downloads section) and in-flight
 * downloads survive screen changes. Downloading runs on its own
 * [CoroutineScope]; state is exposed as Compose state.
 *
 * [initialize] is idempotent and cheap after the first call — call it from
 * app startup (MainActivity / QuranVerseActivity), the Media tab, and the
 * cleanup worker.
 */
object AudioDownloads {

    private var appContext: Context? = null
    private var store: AudioDownloadStore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** All finished downloads, newest first (drives lists and filters). */
    val items = mutableStateOf<List<DownloadItem>>(emptyList())

    /** In-flight / failed downloads keyed by video id. */
    val active = mutableStateMapOf<String, DownloadStatus>()

    /** The video metadata behind each in-flight/failed download (for the UI). */
    val pendingVideos = mutableStateMapOf<String, MediaVideo>()

    /** The resolved audio size in bytes per in-flight download, once known
     *  (set right after stream extraction, BEFORE any bytes are downloaded, so
     *  the UI can show "≈ X MB" while preparing / downloading). Unknown
     *  (0) while extraction is still running. */
    val pendingSizes = mutableStateMapOf<String, Long>()

    // Thread-safe: cancelRequested is written on the main thread and read from
    // IO download threads; connections lets cancel() abort every blocked read
    // immediately (disconnect) instead of waiting out the read timeout. A SET
    // per download because the parallel downloader opens several connections
    // at once (probe + chunks) — cancelling must close ALL of them.
    private val cancelRequested = ConcurrentHashMap<String, Boolean>()
    private val connections = ConcurrentHashMap<String, MutableSet<HttpURLConnection>>()

    // ── Lifecycle ──────────────────────────────────────────────────

@Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        store = AudioDownloadStore(context)
        refresh()
        // One maintenance pass on startup (orphans / stale parts).
        scope.launch { runMaintenance() }
    }

    private fun storeOrThrow(): AudioDownloadStore =
        store ?: throw IllegalStateException("AudioDownloads.initialize() not called")

    // ── Reads ──────────────────────────────────────────────────────

    /** Reloads the download list from disk (call after any storage change). */
    fun refresh() {
        val s = store ?: return
        scope.launch {
            val list = withContext(Dispatchers.IO) { s.loadItems() }
            items.value = list
        }
    }

    fun isDownloaded(videoId: String): Boolean =
        items.value.any { it.videoId == videoId }

    fun itemFor(videoId: String): DownloadItem? =
        items.value.firstOrNull { it.videoId == videoId }

    fun statusFor(videoId: String): DownloadStatus? = active[videoId]

    /** "rss" for feed videos, "url" for manually added ones. */
    fun sourceFor(video: MediaVideo): String =
        DownloadItem.SOURCE_URL.let { url ->
            // The library store is cheap to consult; default to rss.
            try {
                if (com.muddassir.clearview.media.data.MediaLibraryStore(appContext!!)
                        .isManuallyAdded(video.videoId)
                ) url else DownloadItem.SOURCE_RSS
            } catch (e: Exception) {
                DownloadItem.SOURCE_RSS
            }
        }

    // ── Downloading ────────────────────────────────────────────────

    /**
     * Starts (or retries) the audio download for [video]. No-op when the video
     * is already downloaded or a download is still running — but a FAILED
     * download (status [DownloadStatus.Error]) is always restartable: the UI
     * keeps the Error entry in [active] to show the failure and offer Retry,
     * and tapping Retry re-enters here.
     */
    fun download(video: MediaVideo, source: String) {
        val s = storeOrThrow()
        val id = video.videoId
        if (isDownloaded(id)) return
        val current = active[id]
        if (current != null && current !is DownloadStatus.Error) return
        cancelRequested[id] = false
        pendingVideos[id] = video
        scope.launch {
            active[id] = DownloadStatus.Preparing
            try {
                val result = withContext(Dispatchers.IO) {
                    AudioDownloader.download(
                        context = appContext!!,
                        videoId = id,
                        onProgress = { p ->
                            active[id] = DownloadStatus.Downloading(p.fraction, p.etaSeconds)
                        },
                        isCancelled = { cancelRequested[id] == true },
                        onConnection = { conn ->
                            connections.computeIfAbsent(id) {
                                ConcurrentHashMap.newKeySet()
                            }.add(conn)
                        },
                        // As soon as the server's response headers reveal the
                        // size (before any audio bytes flow) it is surfaced so
                        // show "≈ X MB" on the active download.
                        onSizeKnown = { size -> if (size > 0L) pendingSizes[id] = size }
                    )
                }
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val thumbnail = s.downloadThumbnail(id, video.thumbnailUrl)
                    s.upsert(
                        DownloadItem(
                            videoId = id,
                            title = video.title,
                            channelName = video.channelName.ifBlank { video.channelId },
                            channelId = video.channelId,
                            source = source,
                            fileName = result.file.name,
                            fileSize = result.file.length(),
                            downloadedAt = now,
                            lastPlayed = 0L,
                            // No auto-expiry: downloads are kept until the user
                            // removes them manually.
                            expiresAt = 0L,
                            thumbnailPath = thumbnail ?: "",
                            durationSeconds = video.durationSeconds
                        )
                    )
                }
                active.remove(id)
                cancelRequested.remove(id)
                pendingVideos.remove(id)
                pendingSizes.remove(id)
                connections.remove(id)
                refresh()
            } catch (e: CancellationException) {
                cancelRequested.remove(id)
                active.remove(id)
                pendingVideos.remove(id)
                pendingSizes.remove(id)
                connections.remove(id)
                throw e
            } catch (e: Exception) {
                if (cancelRequested[id] == true) {
                    // Cancelled via connection close — treat like a normal cancel.
                    active.remove(id)
                    pendingVideos.remove(id)
                    cancelRequested.remove(id)
                } else {
                    active[id] = DownloadStatus.Error(
                        (e as? DownloadException)?.message ?: "Download failed"
                    )
                }
                pendingSizes.remove(id)
                connections.remove(id)
            }
        }
    }

    /** Cancels the in-flight download for [videoId] (aborts every stream immediately). */
    fun cancel(videoId: String) {
        cancelRequested[videoId] = true
        connections.remove(videoId)?.forEach { conn ->
            runCatching { conn.disconnect() }
        }
    }

    /** Whether [videoId] is being downloaded right now (Preparing or Downloading). */
    fun isActive(videoId: String): Boolean {
        val status = active[videoId]
        return status is DownloadStatus.Preparing || status is DownloadStatus.Downloading
    }

    // ── Device import ──────────────────────────────────────────────

    /**
     * Imports audio files the user picked from the device (SAF document Uris)
     * into the offline library — copied into the audio cache and registered
     * so they appear in the Downloads list and play like any download.
     * [channelId]/[channelName] tag the imports with the Downloads view's
     * current channel scope (so they show up where the user added them).
     * [onResult] fires with the number actually imported, and [onImported]
     * with the [DownloadItem]s created (e.g. to also add them to a playlist).
     */
    fun importFromDevice(
        context: Context,
        uris: List<android.net.Uri>,
        channelId: String = "",
        channelName: String = "",
        onResult: (Int) -> Unit = {},
        onImported: (List<DownloadItem>) -> Unit = {}
    ) {
        val s = storeOrThrow()
        scope.launch {
            // Never leave the caller stuck (a stuck "Importing…" button): any
            // unexpected failure still reports a 0-result so the UI resets.
            val imported = try {
                withContext(Dispatchers.IO) {
                    s.importFromDevice(context, uris, channelId, channelName)
                }
            } catch (e: Exception) {
                emptyList()
            }
            refresh()
            onResult(imported.size)
            onImported(imported)
        }
    }

    // ── Deletion (always allowed) ──────────────────────────────────

    fun delete(videoId: String) {
        if (OfflineAudioPlayer.playingVideoId.value == videoId) OfflineAudioPlayer.stop()
        val s = storeOrThrow()
        scope.launch {
            withContext(Dispatchers.IO) { s.delete(videoId) }
            refresh()
        }
    }

    fun deleteMany(ids: List<String>) {
        // Manual deletion must never be restricted — but deleting the audio
        // that is currently playing should stop it first (like single delete).
        if (OfflineAudioPlayer.playingVideoId.value in ids) OfflineAudioPlayer.stop()
        val s = storeOrThrow()
        scope.launch {
            withContext(Dispatchers.IO) { s.deleteMany(ids.toSet()) }
            refresh()
        }
    }

    fun clearAll() {
        OfflineAudioPlayer.stop()
        // Abort any in-flight download before sweeping the audio dir, so a
        // running download can't keep writing into a deleted .part file.
        active.keys.forEach { cancelRequested[it] = true }
        val s = storeOrThrow()
        scope.launch {
            withContext(Dispatchers.IO) { s.clearAll() }
            refresh()
        }
    }

    /** Records that [videoId] was listened to (drives "recently played"). */
    fun markPlayed(videoId: String) {
        val s = store ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                s.updateLastPlayed(videoId, System.currentTimeMillis())
            }
        }
    }

    // ── Background maintenance ─────────────────────────────────────

    /**
     * Daily-cleanup work (also run once at startup): drop orphaned metadata
     * rows, delete stale .part files from interrupted downloads, and refresh
     * the storage state. Downloads are NEVER deleted here — nothing expires,
     * and nothing is evicted. SUSPEND so callers (the WorkManager worker)
     * actually wait for the cleanup to finish. Returns the number of things
     * removed.
     */
    suspend fun runMaintenance(): Int {
        val s = store ?: return 0
        val removed = withContext(Dispatchers.IO) {
            s.removeOrphanedMetadata() +
                s.deleteStalePartFiles(activeIds = active.keys.toSet())
        }
        refresh()
        return removed
    }

    /** Downloads + thumbnails bytes currently on disk (for the storage card). */
    fun storageUsedBytes(): Long =
        items.value.sumOf { it.fileSize.coerceAtLeast(0L) }
}
