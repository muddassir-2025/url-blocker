package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.model.DownloadSourceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFiltersTest {

    private fun item(videoId: String, source: String) = DownloadItem(
        videoId = videoId,
        title = videoId,
        channelName = "C",
        source = source,
        fileName = "$videoId.m4a",
        fileSize = 100L,
        downloadedAt = 1_700_000_000_000L
    )

    private val items = listOf(
        item("url1", DownloadItem.SOURCE_URL),
        item("dev1", DownloadItem.SOURCE_DEVICE),
        item("rss1", DownloadItem.SOURCE_RSS)
    )

    private fun filtered(filter: DownloadSourceFilter): List<String> =
        items.filter { matchesDownloadSource(it, filter) }.map { it.videoId }

    @Test
    fun `all keeps every download regardless of source`() {
        assertEquals(
            listOf("url1", "dev1", "rss1"),
            filtered(DownloadSourceFilter.ALL)
        )
    }

    @Test
    fun `by url keeps only url sourced downloads`() {
        assertEquals(listOf("url1"), filtered(DownloadSourceFilter.BY_URL))
    }

    @Test
    fun `from device keeps only device imports`() {
        assertEquals(listOf("dev1"), filtered(DownloadSourceFilter.DEVICE))
    }

    @Test
    fun `unknown source values only match the all filter`() {
        val unknown = item("x1", "mystery")
        assertTrue(matchesDownloadSource(unknown, DownloadSourceFilter.ALL))
        assertFalse(matchesDownloadSource(unknown, DownloadSourceFilter.BY_URL))
        assertFalse(matchesDownloadSource(unknown, DownloadSourceFilter.BY_RSS))
        assertFalse(matchesDownloadSource(unknown, DownloadSourceFilter.DEVICE))
    }

    @Test
    fun `by rss keeps only channel-feed downloads`() {
        assertEquals(listOf("rss1"), filtered(DownloadSourceFilter.BY_RSS))
    }
}
