package com.muddassir.clearview.media.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadItemTest {

    @Test
    fun `encode decode round-trip keeps every field`() {
        val original = listOf(
            DownloadItem(
                videoId = "abc123",
                title = "A talk",
                channelName = "Channel",
                source = DownloadItem.SOURCE_URL,
                fileName = "abc123.m4a",
                fileSize = 33_554_432L,
                downloadedAt = 1_700_000_000_000L,
                lastPlayed = 1_700_100_000_000L,
                expiresAt = 1_700_000_000_000L + 15L * 24 * 60 * 60 * 1000,
                thumbnailPath = "abc123.jpg",
                durationSeconds = 654L
            )
        )
        val decoded = DownloadItems.decode(DownloadItems.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `decode tolerates missing optional fields`() {
        val json = """
            [{"videoId":"v1","title":"T","channelName":"C","source":"rss",
              "fileName":"v1.m4a","fileSize":123,"downloadedAt":1}]
        """.trimIndent()
        val decoded = DownloadItems.decode(json)
        assertEquals(1, decoded.size)
        val item = decoded.first()
        assertEquals("v1", item.videoId)
        assertEquals(0L, item.lastPlayed)
        assertEquals(0L, item.expiresAt)
        assertEquals("", item.thumbnailPath)
        assertEquals(0L, item.durationSeconds)
    }

    @Test
    fun `decode handles blank and corrupt input`() {
        assertEquals(emptyList<DownloadItem>(), DownloadItems.decode(null))
        assertEquals(emptyList<DownloadItem>(), DownloadItems.decode(""))
        assertEquals(emptyList<DownloadItem>(), DownloadItems.decode("not json"))
    }

    @Test
    fun `encode of empty list is a valid empty array`() {
        assertEquals("[]", DownloadItems.encode(emptyList()))
    }
}
