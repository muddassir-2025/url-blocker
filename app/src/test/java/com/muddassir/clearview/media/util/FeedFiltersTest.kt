package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.FeedContentFilter
import com.muddassir.clearview.media.model.FeedDateFilter
import com.muddassir.clearview.media.model.FeedFilter
import com.muddassir.clearview.media.model.FeedLibraryFilter
import com.muddassir.clearview.media.model.FeedSortOrder
import com.muddassir.clearview.media.model.FeedWatchStatus
import com.muddassir.clearview.media.model.MediaVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedFiltersTest {

    // Fixed reference time for the relative presets (only differences matter).
    private val now = 1_767_012_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun video(id: String, at: Long, isShort: Boolean = false) = MediaVideo(
        videoId = id,
        title = id,
        channelId = "c",
        channelName = "C",
        publishedAtEpochMillis = at,
        thumbnailUrl = "",
        viewCount = 0L,
        isShort = isShort
    )

    private fun videos(): List<MediaVideo> = listOf(
        video("today", now - 2 * 60 * 60 * 1000),             // 2h ago
        video("yesterday", now - day),                         // 1 day ago
        video("three", now - 3 * day),                         // 3 days ago
        video("eight", now - 8 * day),                         // 8 days ago
        video("forty", now - 40 * day)                         // 40 days ago
    )

    @Test
    fun `default filter returns everything newest first`() {
        val result = applyFeedFilter(videos(), FeedFilter(), now)
        assertEquals(
            listOf("today", "yesterday", "three", "eight", "forty"),
            result.map { it.videoId }
        )
        assertFalse(FeedFilter().isActive)
    }

    @Test
    fun `today filter keeps only today's uploads`() {
        val result = applyFeedFilter(videos(), FeedFilter(date = FeedDateFilter.TODAY), now)
        assertEquals(listOf("today"), result.map { it.videoId })
    }

    @Test
    fun `last 7 days keeps uploads within a week`() {
        val result = applyFeedFilter(videos(), FeedFilter(date = FeedDateFilter.LAST_7_DAYS), now)
        assertEquals(
            listOf("today", "yesterday", "three"),
            result.map { it.videoId }
        )
    }

    @Test
    fun `last 30 days excludes uploads older than a month`() {
        val result = applyFeedFilter(videos(), FeedFilter(date = FeedDateFilter.LAST_30_DAYS), now)
        assertEquals(
            listOf("today", "yesterday", "three", "eight"),
            result.map { it.videoId }
        )
    }

    @Test
    fun `custom range is inclusive on both ends`() {
        val start = now - 8 * day
        val end = now - day
        val filter = FeedFilter(
            date = FeedDateFilter.CUSTOM,
            customStartEpochMillis = start,
            customEndEpochMillis = end
        )
        val result = applyFeedFilter(videos(), filter, now)
        // "eight" (== start) and "yesterday" (== end) are both kept.
        assertEquals(
            listOf("yesterday", "three", "eight"),
            result.map { it.videoId }
        )
    }

    @Test
    fun `content type filters shorts and videos separately`() {
        val all = listOf(
            video("s1", now - day, isShort = true),
            video("v1", now - 2 * day),
            video("s2", now - 3 * day, isShort = true)
        )
        val shorts = applyFeedFilter(all, FeedFilter(content = FeedContentFilter.SHORTS), now)
        assertEquals(listOf("s1", "s2"), shorts.map { it.videoId })
        val longs = applyFeedFilter(all, FeedFilter(content = FeedContentFilter.VIDEOS), now)
        assertEquals(listOf("v1"), longs.map { it.videoId })
    }

    @Test
    fun `oldest first reverses the order`() {
        val result = applyFeedFilter(
            videos(),
            FeedFilter(sort = FeedSortOrder.OLDEST_FIRST),
            now
        )
        assertEquals(
            listOf("forty", "eight", "three", "yesterday", "today"),
            result.map { it.videoId }
        )
    }

    @Test
    fun `isActive is true when anything is non-default`() {
        assertTrue(FeedFilter(date = FeedDateFilter.TODAY).isActive)
        assertTrue(FeedFilter(content = FeedContentFilter.SHORTS).isActive)
        assertTrue(FeedFilter(sort = FeedSortOrder.OLDEST_FIRST).isActive)
    }

    @Test
    fun `summary label includes date and count`() {
        val summary = feedFilterSummary(
            FeedFilter(date = FeedDateFilter.LAST_7_DAYS, watchStatus = FeedWatchStatus.ALL),
            resultCount = 3
        )
        assertEquals("Last 7 days · 3 videos", summary)
    }

    @Test
    fun `summary includes content type when not all`() {
        val summary = feedFilterSummary(
            FeedFilter(
                date = FeedDateFilter.TODAY,
                content = FeedContentFilter.SHORTS,
                watchStatus = FeedWatchStatus.ALL
            ),
            resultCount = 1
        )
        assertEquals("Today · Shorts · 1 video", summary)
    }

    @Test
    fun `summary shows unwatched by default`() {
        val summary = feedFilterSummary(
            FeedFilter(date = FeedDateFilter.TODAY),
            resultCount = 2
        )
        assertEquals("Today · Unwatched · 2 videos", summary)
    }

    @Test
    fun `feed filter encode decode round-trip`() {
        val filter = FeedFilter(
            date = FeedDateFilter.CUSTOM,
            content = FeedContentFilter.SHORTS,
            sort = FeedSortOrder.OLDEST_FIRST,
            customStartEpochMillis = 1_000L,
            customEndEpochMillis = 2_000L
        )
        assertEquals(filter, decodeFeedFilter(encodeFeedFilter(filter)))
    }

    @Test
    fun `default filter round-trips with no custom range`() {
        assertEquals(FeedFilter(), decodeFeedFilter(encodeFeedFilter(FeedFilter())))
    }

    @Test
    fun `decode feed filter handles missing or corrupt input`() {
        assertEquals(null, decodeFeedFilter(null))
        assertEquals(null, decodeFeedFilter(""))
        assertEquals(null, decodeFeedFilter("not json"))
        assertEquals(null, decodeFeedFilter("{\"date\":\"NOPE\"}"))
    }

    @Test
    fun `watch status filters watched unwatched and partial`() {
        val all = listOf(
            video("done", now - day),
            video("partial", now - 2 * day),
            video("never", now - 3 * day)
        )
        val progress: Map<String, Float> = mapOf(
            "done" to 1f,
            "partial" to 0.4f,
            "never" to 0f
        )
        val watched = applyFeedFilter(
            all, FeedFilter(watchStatus = FeedWatchStatus.WATCHED), now,
            progressOf = { progress[it] }
        )
        assertEquals(listOf("done"), watched.map { it.videoId })

        val partial = applyFeedFilter(
            all, FeedFilter(watchStatus = FeedWatchStatus.PARTIALLY_WATCHED), now,
            progressOf = { progress[it] }
        )
        assertEquals(listOf("partial"), partial.map { it.videoId })

        val never = applyFeedFilter(
            all, FeedFilter(watchStatus = FeedWatchStatus.UNWATCHED), now,
            progressOf = { progress[it] }
        )
        assertEquals(listOf("never"), never.map { it.videoId })
    }

    @Test
    fun `unwatched includes videos with no progress at all`() {
        val all = listOf(video("fresh", now - day), video("done", now - 2 * day))
        val result = applyFeedFilter(
            all, FeedFilter(watchStatus = FeedWatchStatus.UNWATCHED), now,
            progressOf = { if (it == "done") 1f else null }
        )
        assertEquals(listOf("fresh"), result.map { it.videoId })
    }

    @Test
    fun `bookmarked filter keeps only bookmarked videos`() {
        val all = listOf(video("a", now - day), video("b", now - 2 * day))
        val result = applyFeedFilter(
            all, FeedFilter(library = FeedLibraryFilter.BOOKMARKED), now,
            bookmarkedOf = { it == "b" }
        )
        assertEquals(listOf("b"), result.map { it.videoId })
    }

    @Test
    fun `date plus watch status combine`() {
        val all = listOf(
            video("today-watched", now - 2 * 60 * 60 * 1000),
            video("today-unwatched", now - 60 * 60 * 1000),
            video("old-watched", now - 5 * day)
        )
        val result = applyFeedFilter(
            all,
            FeedFilter(date = FeedDateFilter.TODAY, watchStatus = FeedWatchStatus.WATCHED),
            now,
            progressOf = { if (it == "today-watched" || it == "old-watched") 1f else 0f }
        )
        assertEquals(listOf("today-watched"), result.map { it.videoId })
    }

    @Test
    fun `old filter values without new keys decode with defaults`() {
        val oldJson = "{\"date\":\"TODAY\",\"content\":\"ALL\",\"sort\":\"NEWEST_FIRST\"}"
        val decoded = decodeFeedFilter(oldJson)
        assertEquals(FeedDateFilter.TODAY, decoded?.date)
        // New default: Unwatched (old persisted values without the key inherit it).
        assertEquals(FeedWatchStatus.UNWATCHED, decoded?.watchStatus)
        assertEquals(FeedLibraryFilter.ALL, decoded?.library)
    }

    @Test
    fun `summary includes watch status and library when set`() {
        val summary = feedFilterSummary(
            FeedFilter(
                date = FeedDateFilter.TODAY,
                watchStatus = FeedWatchStatus.UNWATCHED,
                library = FeedLibraryFilter.BOOKMARKED
            ),
            resultCount = 2
        )
        assertEquals("Today · Unwatched · Bookmarked · 2 videos", summary)
    }

    @Test
    fun `isActive is true for watch status and library filters`() {
        assertTrue(FeedFilter(watchStatus = FeedWatchStatus.WATCHED).isActive)
        assertTrue(FeedFilter(library = FeedLibraryFilter.BOOKMARKED).isActive)
    }

    @Test
    fun `feed filter round-trip keeps new fields`() {
        val filter = FeedFilter(
            date = FeedDateFilter.LAST_7_DAYS,
            watchStatus = FeedWatchStatus.PARTIALLY_WATCHED,
            library = FeedLibraryFilter.BOOKMARKED
        )
        assertEquals(filter, decodeFeedFilter(encodeFeedFilter(filter)))
    }
}
