package com.muddassir.clearview.media.download

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
    /** Connecting / waiting for the server (incl. Render cold starts). */
    data object Preparing : DownloadStatus()

    /** Streaming; [progress] is 0..1, or -1 when the total size is unknown. */
    data class Downloading(val progress: Float) : DownloadStatus()

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

    /** All finished downloads, newest first (drives lists, storage card, filters). */
    val items = mutableStateOf<List<DownloadItem>>(emptyList())

    /** In-flight / failed downloads keyed by video id. */
    val active = mutableStateMapOf<String, DownloadStatus>()

    /** The video metadata behind each in-flight/failed download (for the UI). */
    val pendingVideos = mutableStateMapOf<String, MediaVideo>()

    /** The configured storage limit (bytes) — mirrored for the storage card. */
    val storageLimit = mutableLongStateOf(StoragePolicy.DEFAULT_LIMIT_BYTES)

    // Thread-safe: cancelRequested is written on the main thread and read from
    // IO download threads; connections lets cancel() abort a blocked read
    // immediately (disconnect) instead of waiting out the read timeout.
    private val cancelRequested = ConcurrentHashMap<String, Boolean>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()

    // ── Lifecycle ──────────────────────────────────────────────────

@Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        store = AudioDownloadStore(context)
        storageLimit.longValue = store!!.getStorageLimitBytes()
        refresh()
        // One maintenance pass on startup (expired / orphans / stale parts).
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

    /** Starts (or retries) the audio download for [video]; no-op when done/active. */
    fun download(video: MediaVideo, source: String) {
        val s = storeOrThrow()
        val id = video.videoId
        if (isDownloaded(id) || active.containsKey(id)) return
        cancelRequested[id] = false
        pendingVideos[id] = video
        scope.launch {
            active[id] = DownloadStatus.Preparing
            try {
                val result = withContext(Dispatchers.IO) {
                    // Make room first: drop anything already expired.
                    s.deleteExpired(System.currentTimeMillis())
                    AudioDownloader.download(
                        context = appContext!!,
                        videoId = id,
                        serverUrl = s.getServerUrl(),
                        token = s.getServerToken(),
                        onProgress = { p -> active[id] = DownloadStatus.Downloading(p) },
                        isCancelled = { cancelRequested[id] == true },
                        onConnection = { conn -> connections[id] = conn }
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
                            source = source,
                            fileName = result.file.name,
                            fileSize = result.file.length(),
                            downloadedAt = now,
                            lastPlayed = 0L,
                            expiresAt = StoragePolicy.expiresAtFor(result.file.length(), now),
                            thumbnailPath = thumbnail ?: "",
                            durationSeconds = video.durationSeconds
                        )
                    )
                    // Smart cleanup: over the limit → expired first, then the
                    // oldest — never the audio currently playing.
                    s.evictIfOverLimit(now, protectedIds = currentProtectedIds())
                }
                active.remove(id)
                cancelRequested.remove(id)
                pendingVideos.remove(id)
                connections.remove(id)
                refresh()
            } catch (e: CancellationException) {
                cancelRequested.remove(id)
                active.remove(id)
                pendingVideos.remove(id)
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
                connections.remove(id)
            }
        }
    }

    /** Cancels the in-flight download for [videoId] (aborts the stream immediately). */
    fun cancel(videoId: String) {
        cancelRequested[videoId] = true
        connections.remove(videoId)?.disconnect()
    }

    /** Whether [videoId] is being downloaded right now (Preparing or Downloading). */
    fun isActive(videoId: String): Boolean {
        val status = active[videoId]
        return status is DownloadStatus.Preparing || status is DownloadStatus.Downloading
    }

    private fun currentProtectedIds(): Set<String> =
        OfflineAudioPlayer.playingVideoId.value?.let { setOf(it) } ?: emptySet()

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
     * Daily-cleanup work (also run once at startup): remove expired large
     * downloads, drop orphaned metadata rows, delete stale .part files, and
     * refresh the storage state. SUSPEND so callers (the WorkManager worker)
     * actually wait for the cleanup to finish. Returns the number of things
     * removed.
     */
    suspend fun runMaintenance(): Int {
        val s = store ?: return 0
        val removed = withContext(Dispatchers.IO) {
            s.deleteExpired(System.currentTimeMillis()) +
                s.removeOrphanedMetadata() +
                s.deleteStalePartFiles(activeIds = active.keys.toSet())
        }
        refresh()
        return removed
    }

    // ── Settings ───────────────────────────────────────────────────

    fun serverUrl(): String = storeOrThrow().getServerUrl()

    fun saveServer(url: String, token: String?) {
        val s = storeOrThrow()
        scope.launch {
            withContext(Dispatchers.IO) {
                s.setServerUrl(url)
                s.setServerToken(token)
            }
        }
    }

    fun setStorageLimit(bytes: Long) {
        storageLimit.longValue = bytes
        val s = storeOrThrow()
        scope.launch {
            withContext(Dispatchers.IO) { s.setStorageLimitBytes(bytes) }
        }
    }

    /** Downloads + thumbnails bytes currently on disk (for the storage card). */
    fun storageUsedBytes(): Long =
        items.value.sumOf { it.fileSize.coerceAtLeast(0L) }
}
