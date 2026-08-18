package com.muddassir.clearview.media.download

import org.junit.Assert.assertEquals
import org.junit.Test

class StoragePolicyTest {

    private val now = 1_800_000_000_000L
    private val MB = 1024L * 1024

    private fun item(id: String, size: Long) = DownloadItem(
        videoId = id,
        title = id,
        channelName = "C",
        source = DownloadItem.SOURCE_RSS,
        fileName = "$id.m4a",
        fileSize = size,
        downloadedAt = now
    )

    @Test
    fun `downloads are never auto-deleted`() {
        // No expiry: even a very large download (well over the old 15 MB
        // threshold) is kept indefinitely — only manual deletion removes it.
        val big = item("big", 100L * MB)
        assertEquals(0L, big.expiresAt)
    }

    @Test
    fun `storageUsedBytes sums only non-negative sizes`() {
        val items = listOf(item("a", 10L * MB), item("b", 5L * MB))
        assertEquals(15L * MB, StoragePolicy.storageUsedBytes(items))
    }
}
