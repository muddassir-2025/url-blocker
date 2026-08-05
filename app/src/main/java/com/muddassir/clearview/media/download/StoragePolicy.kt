package com.muddassir.clearview.media.download

/**
 * Pure storage policy for offline audio — no Android dependencies, so the
 * 15-day rule, the 500 MB limit and the eviction order are unit-testable.
 */
object StoragePolicy {

    /** Default storage limit for downloads (spec: 500 MB). */
    const val DEFAULT_LIMIT_BYTES = 500L * 1024 * 1024

    /** Files at or below this size are kept indefinitely (no auto-expiry). */
    const val LARGE_FILE_THRESHOLD_BYTES = 15L * 1024 * 1024

    /** Large downloads expire after this many days. */
    const val EXPIRY_DAYS = 15L
    const val EXPIRY_DAYS_MS = EXPIRY_DAYS * 24L * 60 * 60 * 1000

    /** "Expiring soon" window for the Manage Storage screen. */
    const val EXPIRING_SOON_DAYS = 3L
    const val EXPIRING_SOON_MS = EXPIRING_SOON_DAYS * 24L * 60 * 60 * 1000

    /**
     * The 15-day rule: only downloads larger than
     * [LARGE_FILE_THRESHOLD_BYTES] get an [DownloadItem.expiresAt] (15 days
     * after download). Smaller files are kept indefinitely — they can only be
     * removed manually or by storage-pressure eviction of the oldest items.
     */
    fun expiresAtFor(fileSize: Long, downloadedAt: Long): Long =
        if (fileSize > LARGE_FILE_THRESHOLD_BYTES) downloadedAt + EXPIRY_DAYS_MS else 0L

    /** Sum of all download sizes in bytes. */
    fun storageUsedBytes(items: List<DownloadItem>): Long =
        items.sumOf { it.fileSize.coerceAtLeast(0L) }

    /** Downloads whose 15-day window has passed (large files only). */
    fun expiredItems(items: List<DownloadItem>, now: Long): List<DownloadItem> =
        items.filter { it.expiresAt > 0L && it.expiresAt <= now }

    /** Downloads that expire within [windowMs] (default: the next 3 days). */
    fun expiringSoonItems(
        items: List<DownloadItem>,
        now: Long,
        windowMs: Long = EXPIRING_SOON_MS
    ): List<DownloadItem> =
        items.filter { it.expiresAt > now && it.expiresAt <= now + windowMs }

    /** 0..1 fraction of the limit in use (clamped). */
    fun usageFraction(usedBytes: Long, limitBytes: Long): Float {
        if (limitBytes <= 0L) return 0f
        return (usedBytes.toFloat() / limitBytes).coerceIn(0f, 1f)
    }

    /**
     * Which items to delete in order to free at least [neededBytes]:
     *
     *  1. every EXPIRED download first (oldest first),
     *  2. then the oldest downloads overall (by [downloadedAt], oldest first),
     *
     * accumulating until enough space is freed (or no candidates remain).
     * Items whose videoId is in [protectedIds] (e.g. the audio currently
     * playing) are never returned.
     */
    fun evictionCandidates(
        items: List<DownloadItem>,
        neededBytes: Long,
        now: Long,
        protectedIds: Set<String> = emptySet()
    ): List<DownloadItem> {
        if (neededBytes <= 0L) return emptyList()
        val ordered = items
            .filterNot { it.videoId in protectedIds }
            .sortedWith(
                // Expired first (true > false), then oldest by download time.
                compareByDescending<DownloadItem> { it.isExpired(now) }
                    .thenBy { it.downloadedAt }
            )
        val result = ArrayList<DownloadItem>()
        var freed = 0L
        for (item in ordered) {
            if (freed >= neededBytes) break
            result.add(item)
            freed += item.fileSize.coerceAtLeast(0L)
        }
        return result
    }

    private fun DownloadItem.isExpired(now: Long): Boolean =
        expiresAt > 0L && expiresAt <= now
}
