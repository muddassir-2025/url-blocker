package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.MediaVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaVideosTest {

    private fun video(id: String, at: Long) = MediaVideo(
        videoId = id,
        title = "t-$id",
        channelId = "c",
        channelName = "C",
        publishedAtEpochMillis = at,
        thumbnailUrl = "thumb-$id",
        viewCount = 7L,
        isShort = false
    )

    @Test
    fun `encode decode round-trips all fields`() {
        val list = listOf(video("a", 100L), video("b", 200L))
        assertEquals(list, MediaVideos.decode(MediaVideos.encode(list)))
    }

    @Test
    fun `encode decode round-trips short and live flags`() {
        val list = listOf(
            video("short1", 100L).copy(isShort = true),
            video("live1", 200L).copy(isLive = true),
            video("both", 300L).copy(isShort = true, isLive = true)
        )
        assertEquals(list, MediaVideos.decode(MediaVideos.encode(list)))
    }

    @Test
    fun `encode decode round-trips durationSeconds`() {
        val list = listOf(
            video("long", 100L).copy(durationSeconds = 7542L),
            // Default/unknown duration persists as 0.
            video("unknown", 200L)
        )
        assertEquals(list, MediaVideos.decode(MediaVideos.encode(list)))
    }

    @Test
    fun `decode handles blank and corrupt input`() {
        assertEquals(emptyList<MediaVideo>(), MediaVideos.decode(null))
        assertEquals(emptyList<MediaVideo>(), MediaVideos.decode(""))
        assertEquals(emptyList<MediaVideo>(), MediaVideos.decode("not json"))
    }

    @Test
    fun `merge dedupes by id keeping primary metadata`() {
        val primary = listOf(video("a", 100L), video("b", 200L))
        val supplemental = listOf(
            video("b", 999L).copy(title = "manual-copy"), // same id, older metadata
            video("c", 300L)
        )
        val merged = MediaVideos.merge(primary, supplemental)
        assertEquals(listOf("c", "b", "a"), merged.map { it.videoId })
        // "b" keeps the primary (RSS) copy.
        assertEquals("t-b", merged.first { it.videoId == "b" }.title)
        assertEquals(200L, merged.first { it.videoId == "b" }.publishedAtEpochMillis)
    }

    @Test
    fun `merge sorts newest first`() {
        val merged = MediaVideos.merge(
            listOf(video("old", 100L)),
            listOf(video("new", 500L), video("mid", 300L))
        )
        assertEquals(listOf("new", "mid", "old"), merged.map { it.videoId })
        assertTrue(merged.first().publishedAtEpochMillis > merged.last().publishedAtEpochMillis)
    }
}
