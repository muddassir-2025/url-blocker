package com.muddassir.clearview.media.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val MB = 1024L * 1024

    private fun item(
        id: String,
        size: Long,
        downloadedAt: Long,
        expiresAt: Long = 0L
    ) = DownloadItem(
        videoId = id,
        title = id,
        channelName = "C",
        source = DownloadItem.SOURCE_RSS,
        fileName = "$id.m4a",
        fileSize = size,
        downloadedAt = downloadedAt,
        expiresAt = expiresAt
    )

    @Test
    fun `15 MB or smaller never expires`() {
        assertEquals(0L, StoragePolicy.expiresAtFor(15L * MB, now))
        assertEquals(0L, StoragePolicy.expiresAtFor(1L * MB, now))
    }

    @Test
    fun `larger than 15 MB expires after 15 days`() {
        val at = 1_000L
        assertEquals(at + 15L * day, StoragePolicy.expiresAtFor(15L * MB + 1, at))
        assertEquals(at + 15L * day, StoragePolicy.expiresAtFor(100L * MB, at))
    }

    @Test
    fun `expiredItems only returns large expired files`() {
        val big = item("big", 100L * MB, now - 20L * day, now - 5L * day)
        val smallOld = item("small", 10L * MB, now - 20L * day) // no expiry
        val notYet = item("soon", 30L * MB, now - 10L * day, now + 5L * day)
        assertEquals(listOf("big"), StoragePolicy.expiredItems(listOf(big, smallOld, notYet), now).map { it.videoId })
    }

    @Test
    fun `expiringSoonItems lists files expiring within the window`() {
        val soon = item("soon", 30L * MB, now - 14L * day, now + 1L * day)
        val later = item("later", 30L * MB, now - 10L * day, now + 10L * day)
        assertEquals(listOf("soon"), StoragePolicy.expiringSoonItems(listOf(soon, later), now).map { it.videoId })
    }

    @Test
    fun `usageFraction clamps between 0 and 1`() {
        assertEquals(0.5f, StoragePolicy.usageFraction(250L * MB, 500L * MB), 0.0001f)
        assertEquals(1f, StoragePolicy.usageFraction(600L * MB, 500L * MB), 0.0001f)
        assertEquals(0f, StoragePolicy.usageFraction(0L, 500L * MB), 0.0001f)
        assertEquals(0f, StoragePolicy.usageFraction(100L * MB, 0L), 0.0001f)
    }

    @Test
    fun `eviction frees exactly the needed space from the oldest first`() {
        val items = listOf(
            item("a", 10L * MB, now - 5L * day),
            item("b", 20L * MB, now - 4L * day),
            item("c", 30L * MB, now - 3L * day),
            item("d", 40L * MB, now - 2L * day)
        )
        // Need 25 MB: deletes "a" (10) + "b" (20) = 30 MB freed.
        val candidates = StoragePolicy.evictionCandidates(items, 25L * MB, now)
        assertEquals(listOf("a", "b"), candidates.map { it.videoId })
    }

    @Test
    fun `eviction deletes expired downloads first`() {
        val items = listOf(
            item("old", 5L * MB, now - 6L * day),                 // oldest but small
            item("expired", 10L * MB, now - 3L * day, now - 1L * day), // expired
            item("new", 20L * MB, now - 1L * day)
        )
        val candidates = StoragePolicy.evictionCandidates(items, 12L * MB, now)
        assertEquals(listOf("expired", "old"), candidates.map { it.videoId })
    }

    @Test
    fun `eviction never touches protected ids`() {
        val items = listOf(
            item("a", 50L * MB, now - 5L * day),
            item("playing", 60L * MB, now - 1L * day)
        )
        val candidates = StoragePolicy.evictionCandidates(items, 40L * MB, now, protectedIds = setOf("playing"))
        assertEquals(listOf("a"), candidates.map { it.videoId })
        // Everything would be deleted if nothing were protected.
        val unprotected = StoragePolicy.evictionCandidates(items, 120L * MB, now)
        assertTrue(unprotected.size == 2)
    }

    @Test
    fun `eviction stops once enough space is freed`() {
        val items = listOf(
            item("a", 100L * MB, now - 5L * day),
            item("b", 100L * MB, now - 4L * day)
        )
        val candidates = StoragePolicy.evictionCandidates(items, 1L * MB, now)
        assertEquals(listOf("a"), candidates.map { it.videoId })
    }

    @Test
    fun `eviction with nothing needed is empty`() {
        val items = listOf(item("a", 100L * MB, now))
        assertTrue(StoragePolicy.evictionCandidates(items, 0L, now).isEmpty())
        assertTrue(StoragePolicy.evictionCandidates(items, -5L, now).isEmpty())
    }

    @Test
    fun `storageUsedBytes sums only non-negative sizes`() {
        val items = listOf(item("a", 10L * MB, now), item("b", 5L * MB, now))
        assertEquals(15L * MB, StoragePolicy.storageUsedBytes(items))
    }
}
