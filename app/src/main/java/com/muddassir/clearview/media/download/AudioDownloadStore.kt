package com.muddassir.clearview.media.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Unified storage for offline audio downloads:
 *
 * ```
 * filesDir/downloads/<videoId>.<ext> ← the COMPLETED audio files (permanent)
 * filesDir/downloads/metadata.json   ← the download registry
 * cache/audio/<videoId>.part         ← temporary in-progress downloads
 * cache/thumbnails/<videoId>.jpg     ← local thumbnails (offline lists/player)
 * ```
 *
 * Completed downloads live in `filesDir/downloads` — the app's permanent
 * storage — so clearing the cache (or Android doing it automatically) can
 * never delete the user's saved audio. Only genuinely temporary data (the
 * in-progress `.part` files, thumbnails) uses the cache. The registry
 * ([DownloadItem]s) is persisted as JSON in metadata.json so it survives
 * restarts. All heavy I/O is expected to run on the caller's background
 * dispatcher.
 */
class AudioDownloadStore(context: Context) {

    private val appContext = context.applicationContext

    /** Completed downloads: `filesDir/downloads` (permanent, cache-safe). */
    val audioDir: File = File(appContext.filesDir, "downloads")
    /** Temporary in-progress downloads: `cache/audio` (may be cleared anytime). */
    val partsDir: File = File(appContext.cacheDir, "audio")
    val thumbnailsDir: File = File(appContext.cacheDir, "thumbnails")
    private val metadataFile: File = File(audioDir, "metadata.json")

    init {
        audioDir.mkdirs()
        partsDir.mkdirs()
        thumbnailsDir.mkdirs()
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

    // ── Files ──────────────────────────────────────────────────────

    fun audioFile(item: DownloadItem): File = File(audioDir, item.fileName)

    fun thumbnailFile(item: DownloadItem): File = File(thumbnailsDir, item.thumbnailPath)

    fun partFile(videoId: String): File = File(partsDir, "$videoId.part")

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

    /**
     * Imports audio files the user picked from the device (SAF document Uris)
     * into the offline library: each file is COPIED into [audioDir] and
     * registered as a [DownloadItem] (source [DownloadItem.SOURCE_DEVICE]) so
     * it appears in the Downloads list and plays through [OfflineAudioPlayer]
     * exactly like a downloaded one. Returns the [DownloadItem]s actually
     * imported (callers can e.g. add them to a user playlist).
     *
     * [channelId]/[channelName] tag the import with the Downloads view's
     * current channel scope (if any), so the new items show up right where
     * the user added them instead of silently appearing only in "All".
     */
    fun importFromDevice(
        context: Context,
        uris: List<Uri>,
        channelId: String = "",
        channelName: String = ""
    ): List<DownloadItem> = synchronized(LOCK) {
        val created = mutableListOf<DownloadItem>()
        var imported = 0
        uris.forEach { uri ->
            try {
                val displayName = queryDisplayName(context, uri)
                    ?: "Audio ${imported + 1}"
                val ext = displayName.substringAfterLast('.', "m4a")
                    .takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }
                    ?: "m4a"
                val id = "device-" + UUID.randomUUID().toString().take(8)
                val fileName = "$id.$ext"
                val target = File(audioDir, fileName)
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                } ?: -1L
                if (copied <= 0L || !target.exists()) {
                    target.delete()
                    return@forEach
                }
                val now = System.currentTimeMillis()
                val item = DownloadItem(
                    videoId = id,
                    title = displayName.substringBeforeLast('.', displayName)
                        .ifBlank { displayName },
                    channelName = channelName.ifBlank { "From device" },
                    source = DownloadItem.SOURCE_DEVICE,
                    fileName = fileName,
                    fileSize = target.length(),
                    downloadedAt = now,
                    lastPlayed = 0L,
                    // The user's own files are kept forever — like every other
                    // download, nothing is ever auto-deleted.
                    expiresAt = 0L,
                    thumbnailPath = "",
                    // Real track length so the Downloads list shows "3:24"
                    // instead of hiding the duration (0 = unreadable file).
                    durationSeconds = readDurationSeconds(target),
                    channelId = channelId
                )
                upsert(item)
                created += item
                imported++
            } catch (e: Exception) {
                // Unreadable / non-audio pick → skip it, keep importing the rest.
            }
        }
        created
    }

    /**
     * Reads a copied audio file's duration in whole seconds via
     * [MediaMetadataRetriever], or 0 when it can't be read (unsupported or
     * corrupt container). The retriever is always released, even on failure.
     */
    private fun readDurationSeconds(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?.div(1000L)
                ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** The picker's display name for [uri] (from OpenableColumns), or null. */
    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
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

    /**
     * One-time migration from the legacy `cache/audio/` location: completed
     * downloads (audio files + the metadata registry) move to the permanent
     * `filesDir/downloads/` home, where a cache clear can no longer delete
     * them. Called on startup by [AudioDownloads.initialize].
     *
     * Safe, idempotent and restart-friendly:
     *  - `.part` (in-progress) and `.tmp` (atomic metadata writes) files are
     *    genuinely temporary and STAY in the cache — they are already where
     *    they belong,
     *  - audio files move first, the registry LAST, so an interruption can
     *    never leave a moved registry pointing at files that never arrived
     *    (worst case: a still-cached registry, which the next run finishes
     *    moving — rows are never orphaned),
     *  - a destination that already holds the same file (same size) is a
     *    previously completed migration — its legacy copy is removed,
     *  - a name clash with different content keeps BOTH copies and logs,
     *  - nothing is deleted before the destination is verified; failures keep
     *    the original file and log instead of deleting it.
     */
    fun migrateLegacyDownloads() {
        // cache/audio is now the TEMPORARY parts home — if no legacy downloads
        // ever lived there it only ever holds .part files, which need no
        // migration (and the dir must not be deleted: it is in active use).
        val legacy = partsDir
        if (!legacy.isDirectory) return
        val files = legacy.listFiles() ?: return
        if (files.none { isCompletedDownload(it) }) return
        audioDir.mkdirs()
        var moved = 0
        files.forEach { f ->
            if (f.name == "metadata.json") return@forEach // moves last
            if (isCompletedDownload(f) && moveCompletedFile(f)) moved++
        }
        // The registry moves LAST: a crash mid-migration leaves it in the
        // cache (harmless — the next run finishes the job) instead of a moved
        // registry pointing at files that never made it across.
        val metadata = File(legacy, "metadata.json")
        if (metadata.isFile && moveCompletedFile(metadata)) moved++
        if (moved > 0) {
            Log.i(TAG, "Migrated $moved legacy download(s) to filesDir/downloads")
        }
    }

    /** True for a completed download: a plain file that is not a temp write. */
    private fun isCompletedDownload(f: File): Boolean {
        if (!f.isFile) return false
        val name = f.name
        return !name.endsWith(".part") && !name.endsWith(".tmp")
    }

    /** Moves one legacy file into [audioDir]; true when it now lives there. */
    private fun moveCompletedFile(src: File): Boolean {
        val dest = File(audioDir, src.name)
        return try {
            when {
                !dest.exists() -> moveFile(src, dest)
                // Already migrated on a previous run (same file) — drop the
                // cache copy so the migration converges.
                dest.length() == src.length() -> {
                    src.delete()
                    true
                }
                // Name clash with DIFFERENT content: never overwrite, never
                // delete — keep both copies and surface it.
                else -> {
                    Log.w(TAG, "Legacy name clash kept in cache: ${src.name}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy migration failed for ${src.name}: ${e.message}")
            false
        }
    }

    /** Moves [src] to [dest], falling back to copy+verify when rename fails. */
    private fun moveFile(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        // renameTo can fail across some filesystems — fall back to a verified
        // copy; the source is deleted ONLY after the destination checks out.
        return try {
            src.copyTo(dest, overwrite = false)
            val ok = dest.exists() && dest.length() == src.length()
            if (ok) src.delete() else dest.delete()
            ok
        } catch (e: Exception) {
            runCatching { dest.delete() }
            false
        }
    }

    /** Deletes stale `.part` files (interrupted downloads), skipping [activeIds]. */
    fun deleteStalePartFiles(activeIds: Set<String> = emptySet(), olderThanMs: Long = STALE_PART_MS): Int {
        val cutoff = System.currentTimeMillis() - olderThanMs
        var count = 0
        partsDir.listFiles()?.forEach { f ->
            val name = f.name
            if (name.endsWith(".part") && f.lastModified() < cutoff &&
                name.removeSuffix(".part") !in activeIds
            ) {
                if (f.delete()) count++
            }
        }
        return count
    }

    private companion object {
        // Serializes every read-modify-write of metadata.json across ALL store
        // instances (the manager, the Downloads sheet, the worker) so two
        // concurrent downloads finishing together can never drop each other's
        // metadata row (which would orphan a file on disk).
        val LOCK = Any()

        private const val TAG = "AudioDownloadStore"

        const val STALE_PART_MS = 24L * 60 * 60 * 1000
    }
}
