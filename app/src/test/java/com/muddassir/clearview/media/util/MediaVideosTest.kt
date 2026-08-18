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
    fun `encode decode round-trips the offline-audio flag`() {
        val list = listOf(
            // A playlist can hold BOTH a video and its downloaded audio.
            video("same", 100L),
            video("same", 100L).copy(isOfflineAudio = true),
            video("plain", 200L)
        )
        val decoded = MediaVideos.decode(MediaVideos.encode(list))
        assertEquals(list, decoded)
        // The flag is persisted (not just a runtime default).
        assertTrue(decoded[1].isOfflineAudio)
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

    // The Media-tab feed merge (fresh + cached): fresh (RSS, just fetched —
    // carries this round's enriched durations) wins for overlap, while videos
    // only present in the cache are KEPT — a channel whose refresh failed
    // this round must not vanish from the feed. This is the regression guard
    // for the "newly added channel never shows its feed" bug.
    @Test
    fun `feed merge prefers fresh but keeps cached-only videos`() {
        // Cached state: channel A's videos + channel B's videos (B is the
        // newly added channel, cache written by addChannel without durations).
        val cached = listOf(
            video("a1", 100L).copy(durationSeconds = 0L), // B's fresh copy missing → cached must stay
            video("b1", 200L).copy(durationSeconds = 0L)
        )
        // Fresh refresh this round: A succeeded (enriched), B FAILED (absent).
        val fresh = listOf(
            video("a1", 100L).copy(durationSeconds = 7542L, viewCount = 9L)
        )
        val merged = MediaVideos.merge(fresh, cached)
        // Both channels present — B did not vanish despite its failed refresh.
        assertEquals(setOf("a1", "b1"), merged.map { it.videoId }.toSet())
        // A keeps the FRESH (enriched) copy, not the stale cached one.
        val a1 = merged.first { it.videoId == "a1" }
        assertEquals(7542L, a1.durationSeconds)
        assertEquals(9L, a1.viewCount)
    }
}
