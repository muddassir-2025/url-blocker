package com.muddassir.clearview.media.download

/**
 * Pure storage helpers for offline audio — no Android dependencies, so the
 * accounting is unit-testable.
 *
 * Downloads are NEVER deleted automatically: there is no age-based expiry and
 * no storage-limit eviction. Everything stays until the user removes it
 * manually (or clears the app cache). Only the byte accounting used by the
 * Downloads screen lives here.
 */
object StoragePolicy {

    /** Sum of all download sizes in bytes (non-negative only). */
    fun storageUsedBytes(items: List<DownloadItem>): Long =
        items.sumOf { it.fileSize.coerceAtLeast(0L) }
}
