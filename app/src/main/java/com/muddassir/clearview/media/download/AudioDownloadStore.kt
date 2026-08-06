package com.muddassir.clearview.media.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Unified storage for offline audio downloads:
 *
 * ```
 * cache/audio/<videoId>.<ext>   ← the audio files
 * cache/audio/metadata.json     ← the download registry
 * cache/thumbnails/<videoId>.jpg ← local thumbnails (offline lists/player)
 * ```
 *
 * The registry ([DownloadItem]s) is persisted as JSON in metadata.json so it
 * survives restarts. All heavy I/O is expected to run on the caller's
 * background dispatcher.
 *
 * Also owns the persisted storage limit, which lives in SharedPreferences.
 */
class AudioDownloadStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val audioDir: File = File(appContext.cacheDir, "audio")
    val thumbnailsDir: File = File(appContext.cacheDir, "thumbnails")
    private val metadataFile: File = File(audioDir, "metadata.json")

    init {
        audioDir.mkdirs()
        thumbnailsDir.mkdirs()
    }

    // ── Storage limit ──────────────────────────────────────────────

    fun getStorageLimitBytes(): Long =
        prefs.getLong(KEY_STORAGE_LIMIT, StoragePolicy.DEFAULT_LIMIT_BYTES)

    fun setStorageLimitBytes(limit: Long) {
        prefs.edit().putLong(KEY_STORAGE_LIMIT, limit.coerceAtLeast(10L * 1024 * 1024)).apply()
    }

    // ── Metadata registry ──────────────────────────────────────────

    /** All downloads, newest first. */
    fun loadItems(): List<DownloadItem> = synchronized(LOCK) {
        DownloadItems.decode(metadataFile.takeIf { it.exists() }?.readText() ?: "")
            .sortedByDescending { it.downloadedAt }
    }

    /**
     * Persists the registry ATOMICALLY (temp file + rename), so a crash
     * mid-write can never corrupt metadata.json and "lose" every download.
     */
    private fun saveItems(items: List<DownloadItem>) {
        try {
            val tmp = File(audioDir, "metadata.json.tmp")
            tmp.writeText(DownloadItems.encode(items), Charsets.UTF_8)
            if (!tmp.renameTo(metadataFile)) {
                metadataFile.delete()
                tmp.renameTo(metadataFile)
            }
        } catch (e: Exception) {
            // Never let a metadata write failure crash a download.
        }
    }

    fun upsert(item: DownloadItem) = synchronized(LOCK) {
        saveItems(loadItems().filterNot { it.videoId == item.videoId } + item)
    }

    fun updateLastPlayed(videoId: String, at: Long) = synchronized(LOCK) {
        val items = loadItems().toMutableList()
        val index = items.indexOfFirst { it.videoId == videoId }
        if (index < 0) return@synchronized
        items[index] = items[index].copy(lastPlayed = at)
        saveItems(items)
    }

    /** Deletes the item's metadata AND its audio + thumbnail files. */
    fun delete(videoId: String): Boolean = synchronized(LOCK) {
        val items = loadItems()
        val item = items.firstOrNull { it.videoId == videoId } ?: return@synchronized false
        audioFile(item).delete()
        if (item.thumbnailPath.isNotBlank()) {
            File(thumbnailsDir, item.thumbnailPath).delete()
        }
        saveItems(items.filterNot { it.videoId == videoId })
        true
    }

    fun deleteMany(ids: Set<String>) {
        ids.forEach { delete(it) }
    }

    fun clearAll() {
        loadItems().forEach { delete(it.videoId) }
        // Sweep leftovers (part files, unmatched thumbnails, …).
        audioDir.listFiles()?.forEach { it.delete() }
        thumbnailsDir.listFiles()?.forEach { it.delete() }
        saveItems(emptyList())
    }

    // ── Files ──────────────────────────────────────────────────────

    fun audioFile(item: DownloadItem): File = File(audioDir, item.fileName)

    fun thumbnailFile(item: DownloadItem): File = File(thumbnailsDir, item.thumbnailPath)

    fun partFile(videoId: String): File = File(audioDir, "$videoId.part")

    /**
     * Downloads the video thumbnail into `thumbnails/<videoId>.jpg` so the
     * downloads list and the offline player work without a network. Returns
     * the relative file name, or null on failure.
     */
    fun downloadThumbnail(videoId: String, url: String): String? {
        if (url.isBlank()) return null
        val target = File(thumbnailsDir, "$videoId.jpg")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return null
                target.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
            }
            target.name
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ── Maintenance ────────────────────────────────────────────────

    /** Removes registry rows whose audio file no longer exists; returns count. */
    fun removeOrphanedMetadata(): Int = synchronized(LOCK) {
        val items = loadItems()
        val orphans = items.filterNot { audioFile(it).exists() }
        if (orphans.isEmpty()) return@synchronized 0
        val orphanIds = orphans.map { it.videoId }.toSet()
        saveItems(items.filterNot { it.videoId in orphanIds })
        orphans.size
    }

    /** Deletes stale `.part` files (interrupted downloads), skipping [activeIds]. */
    fun deleteStalePartFiles(activeIds: Set<String> = emptySet(), olderThanMs: Long = STALE_PART_MS): Int {
        val cutoff = System.currentTimeMillis() - olderThanMs
        var count = 0
        audioDir.listFiles()?.forEach { f ->
            val name = f.name
            if (name.endsWith(".part") && f.lastModified() < cutoff &&
                name.removeSuffix(".part") !in activeIds
            ) {
                if (f.delete()) count++
            }
        }
        return count
    }

    /** Deletes expired downloads (the 15-day rule); returns count. */
    fun deleteExpired(now: Long): Int = synchronized(LOCK) {
        val expired = StoragePolicy.expiredItems(loadItems(), now)
        expired.forEach { delete(it.videoId) }
        expired.size
    }

    /**
     * Smart cleanup after a download: if storage exceeds the limit, delete
     * expired downloads first, then the oldest downloads — never the audio
     * currently playing ([protectedIds]). Returns how many were deleted.
     */
    fun evictIfOverLimit(now: Long, protectedIds: Set<String>): Int = synchronized(LOCK) {
        val items = loadItems()
        val used = StoragePolicy.storageUsedBytes(items)
        val limit = getStorageLimitBytes()
        if (used <= limit) return@synchronized 0
        val candidates = StoragePolicy.evictionCandidates(items, used - limit, now, protectedIds)
        candidates.forEach { delete(it.videoId) }
        candidates.size
    }

    private companion object {
        // Serializes every read-modify-write of metadata.json across ALL store
        // instances (the manager, the Downloads sheet, the worker) so two
        // concurrent downloads finishing together can never drop each other's
        // metadata row (which would orphan a file on disk).
        val LOCK = Any()

        const val PREFS_NAME = "audio_downloads"
        const val KEY_STORAGE_LIMIT = "storage_limit"

        const val STALE_PART_MS = 24L * 60 * 60 * 1000
    }
}
