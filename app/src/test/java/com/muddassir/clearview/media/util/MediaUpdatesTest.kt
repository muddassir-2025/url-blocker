package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.MediaChannelUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUpdatesTest {

    private fun update(id: String, channel: String = "C", at: Long = 0L) = MediaChannelUpdate(
        channelId = channel,
        channelName = channel,
        latestVideoId = id,
        latestVideoTitle = "Video $id",
        publishedAtEpochMillis = at
    )

    @Test
    fun `merge caps the feed at ten newest-first`() {
        val incoming = (1..12).map { update("v$it", at = it.toLong()) }
        val merged = MediaUpdates.merge(emptyList(), incoming)
        assertEquals(10, merged.size)
        // Newest first.
        assertEquals("v12", merged.first().latestVideoId)
        assertEquals("v3", merged.last().latestVideoId)
    }

    @Test
    fun `merge dedupes by video id keeping the newer entry`() {
        val existing = listOf(update("v1", at = 100))
        val incoming = listOf(update("v1", at = 200), update("v2", at = 150))
        val merged = MediaUpdates.merge(existing, incoming)
        assertEquals(2, merged.size)
        // The newer v1 wins; v2 kept.
        assertEquals(200L, merged.first { it.latestVideoId == "v1" }.publishedAtEpochMillis)
        assertTrue(merged.any { it.latestVideoId == "v2" })
    }

    @Test
    fun `merge keeps existing history unchanged when incoming is empty`() {
        val existing = (1..3).map { update("v$it", at = it.toLong()) }
        val merged = MediaUpdates.merge(existing, emptyList())
        // Same entries, but re-sorted newest-first (v3 is newest).
        assertEquals(listOf("v3", "v2", "v1"), merged.map { it.latestVideoId })
    }

    @Test
    fun `encode and decode round-trip`() {
        val updates = listOf(
            update("a", channel = "Safina Society", at = 111),
            update("b", at = 222)
        )
        assertEquals(updates, MediaUpdates.decode(MediaUpdates.encode(updates)))
    }

    @Test
    fun `decode handles blank or corrupt input`() {
        assertEquals(emptyList<MediaChannelUpdate>(), MediaUpdates.decode(null))
        assertEquals(emptyList<MediaChannelUpdate>(), MediaUpdates.decode(""))
        assertEquals(emptyList<MediaChannelUpdate>(), MediaUpdates.decode("not json"))
    }

    @Test
    fun `dismiss filtering removes exactly the dismissed entry`() {
        val history = (1..3).map { update("v$it", at = it.toLong()) }
        val remaining = history.filterNot { it.latestVideoId == "v2" }
        assertEquals(listOf("v1", "v3"), remaining.map { it.latestVideoId })
    }
}
