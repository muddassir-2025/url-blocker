package com.muddassir.clearview.media.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPageParserTest {

    // A realistic playlist page: the embedded ytInitialData JSON carries the
    // title + the ordered playlistVideoListRenderer entries (one of which has
    // no id — the "private video" placeholder that must be skipped).
    private val sampleHtml = """
        <!DOCTYPE html><html><head><script>var ytInitialData = {
          "header": {
            "playlistHeaderRenderer": { "title": { "runs": [ { "text": "My Cool Playlist" } ] } }
          },
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "itemSectionRenderer": {
                              "contents": [
                                {
                                  "playlistVideoListRenderer": {
                                    "contents": [
                                      { "playlistVideoRenderer": { "videoId": "vid1", "title": {"runs": [{"text": "First Video"}]}, "index": {"simpleText": "1"}, "lengthText": {"simpleText": "12:34"}, "shortBylineText": {"runs": [{"text": "Channel One"}]}, "longBylineText": {"runs": [{"text": "Channel One", "navigationEndpoint": {"browseEndpoint": {"browseId": "UC123"}}}]}, "videoInfo": {"runs": [{"text": "1,234,567 views"}, {"text": "3 days ago"}]}, "publishedTimeText": {"simpleText": "3 days ago"} } },
                                      { "playlistVideoRenderer": { "videoId": "vid2", "title": {"simpleText": "Second Video #shorts"}, "index": {"simpleText": "2"}, "lengthText": {"simpleText": "0:45"}, "shortBylineText": {"runs": [{"text": "Channel Two"}]}, "longBylineText": {"runs": [{"text": "Channel Two", "navigationEndpoint": {"browseEndpoint": {"browseId": "UC456"}}}]}, "publishedTimeText": {"simpleText": "2 weeks ago"} } },
                                      { "playlistVideoRenderer": { "videoId": "", "title": {"runs": [{"text": "Private video"}]} } }
                                    ]
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        };</script></head><body></body></html>
    """.trimIndent()

    @Test
    fun `parses title and ordered videos from the page`() {
        val info = PlaylistPageParser.parsePage(sampleHtml)
        assertEquals("My Cool Playlist", info?.title)
        assertEquals(listOf("vid1", "vid2"), info?.videos?.map { it.videoId })
    }

    @Test
    fun `parses video metadata`() {
        val info = PlaylistPageParser.parsePage(sampleHtml)!!
        val first = info.videos[0]
        assertEquals("First Video", first.title)
        assertEquals("Channel One", first.channelName)
        assertEquals("UC123", first.channelId)
        assertEquals(754L, first.durationSeconds) // 12:34
        assertEquals(1_234_567L, first.viewCount)
        assertEquals("https://i.ytimg.com/vi/vid1/hqdefault.jpg", first.thumbnailUrl)
        // Relative publish label → a recent epoch (within the last ~4 days).
        val threeDays = 3L * 24 * 60 * 60 * 1000
        assertTrue(first.publishedAtEpochMillis > 0L)
        assertTrue(
            System.currentTimeMillis() - first.publishedAtEpochMillis in
                threeDays - 60_000L..threeDays + 60_000L
        )
    }

    @Test
    fun `classifies shorts by title hashtag`() {
        val info = PlaylistPageParser.parsePage(sampleHtml)!!
        assertTrue(info.videos[1].isShort)
    }

    @Test
    fun `skips entries without a video id`() {
        val info = PlaylistPageParser.parsePage(sampleHtml)!!
        assertEquals(2, info.videos.size)
    }

    @Test
    fun `returns null when the page has no ytInitialData`() {
        assertNull(PlaylistPageParser.parsePage("<html><body>nothing here</body></html>"))
        assertNull(PlaylistPageParser.parsePage(""))
    }

    @Test
    fun `private playlist page yields title with no videos`() {
        val privateHtml = """
            <script>var ytInitialData = {"header":{"playlistHeaderRenderer":{"title":{"simpleText":"Secret List"}}},"contents":{}};</script>
        """.trimIndent()
        val info = PlaylistPageParser.parsePage(privateHtml)
        assertEquals("Secret List", info?.title)
        assertTrue(info!!.videos.isEmpty())
    }

    @Test
    fun `parseClock handles common formats`() {
        assertEquals(754L, PlaylistPageParser.parseClock("12:34"))
        assertEquals(3723L, PlaylistPageParser.parseClock("1:02:03"))
        assertEquals(45L, PlaylistPageParser.parseClock("0:45"))
        assertEquals(0L, PlaylistPageParser.parseClock(""))
        assertEquals(0L, PlaylistPageParser.parseClock("bogus"))
        // Live entries carry no duration.
        assertEquals(0L, PlaylistPageParser.parseClock("LIVE"))
    }

    @Test
    fun `parseRelativeTimeAgo converts relative labels`() {
        val hour = 60L * 60 * 1000
        val day = 24L * hour
        val oneHourAgo = PlaylistPageParser.parseRelativeTimeAgo("1 hour ago")
        assertTrue(System.currentTimeMillis() - oneHourAgo in hour - 60_000L..hour + 60_000L)
        val threeDaysAgo = PlaylistPageParser.parseRelativeTimeAgo("3 days ago")
        assertTrue(
            System.currentTimeMillis() - threeDaysAgo in
                3 * day - 60_000L..3 * day + 60_000L
        )
        assertEquals(0L, PlaylistPageParser.parseRelativeTimeAgo(""))
        assertEquals(0L, PlaylistPageParser.parseRelativeTimeAgo("Premieres tomorrow"))
        assertEquals(0L, PlaylistPageParser.parseRelativeTimeAgo("garbage"))
    }
}
