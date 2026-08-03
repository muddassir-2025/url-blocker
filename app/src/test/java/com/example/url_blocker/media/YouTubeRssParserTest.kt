package com.example.url_blocker.media

import com.example.url_blocker.media.data.ChannelIdResolver
import com.example.url_blocker.media.data.YouTubeRssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeRssParserTest {

    private val sampleFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
          <id>yt:channel:UC2cX3SmsdWsrRS8t_5zvzEw</id>
          <yt:channelId>UC2cX3SmsdWsrRS8t_5zvzEw</yt:channelId>
          <title>Safina Society</title>
          <entry>
            <id>yt:video:abc123XYZ</id>
            <yt:videoId>abc123XYZ</yt:videoId>
            <yt:channelId>UC2cX3SmsdWsrRS8t_5zvzEw</yt:channelId>
            <title>Beautiful Recitation</title>
            <link rel="alternate" href="http://www.youtube.com/watch?v=abc123XYZ"/>
            <author><name>Safina Society</name><uri>http://www.youtube.com/channel/UC2cX3SmsdWsrRS8t_5zvzEw</uri></author>
            <published>2024-03-10T08:30:00+00:00</published>
            <updated>2024-03-10T08:31:00+00:00</updated>
            <media:group>
              <media:title>Beautiful Recitation</media:title>
              <media:thumbnail url="https://i.ytimg.com/vi/abc123XYZ/hqdefault.jpg" width="480" height="360"/>
            </media:group>
          </entry>
          <entry>
            <id>yt:video:def456QRS</id>
            <yt:videoId>def456QRS</yt:videoId>
            <yt:channelId>UC2cX3SmsdWsrRS8t_5zvzEw</yt:channelId>
            <title>Short Lecture</title>
            <author><name>Safina Society</name></author>
            <published>2024-02-01T00:00:00+00:00</published>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parsesEntriesIntoVideos() {
        val videos = YouTubeRssParser.parse(sampleFeed)

        assertEquals(2, videos.size)

        val first = videos[0]
        assertEquals("abc123XYZ", first.videoId)
        assertEquals("Beautiful Recitation", first.title)
        assertEquals("Safina Society", first.channelName)
        assertEquals("UC2cX3SmsdWsrRS8t_5zvzEw", first.channelId)
        // 2024-03-10T08:30:00Z
        assertEquals(1710059400000L, first.publishedAtEpochMillis)
        assertEquals("https://i.ytimg.com/vi/abc123XYZ/hqdefault.jpg", first.thumbnailUrl)
    }

    @Test
    fun fallsBackToGeneratedThumbnailWhenFeedOmitsMediaThumbnail() {
        val videos = YouTubeRssParser.parse(sampleFeed)
        val second = videos[1]
        assertEquals("def456QRS", second.videoId)
        assertEquals("Short Lecture", second.title)
        // No media:thumbnail in the feed entry → generated hqdefault URL.
        assertEquals(
            YouTubeRssParser.fallbackThumbnail("def456QRS"),
            second.thumbnailUrl
        )
    }

    @Test
    fun returnsEmptyListForMalformedXml() {
        assertTrue(YouTubeRssParser.parse("this is not xml at all").isEmpty())
        assertTrue(YouTubeRssParser.parse("").isEmpty())
    }

    @Test
    fun skipsEntriesWithoutVideoId() {
        val feed = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><title>No id here</title></entry>
              <entry>
                <yt:videoId xmlns:yt="http://www.youtube.com/xml/schemas/2015">valid123</yt:videoId>
                <title>Valid</title>
              </entry>
            </feed>
        """.trimIndent()
        val videos = YouTubeRssParser.parse(feed)
        assertEquals(1, videos.size)
        assertEquals("valid123", videos[0].videoId)
    }
}

class ChannelIdResolverTest {

    @Test
    fun acceptsBareChannelId() {
        assertEquals(
            "UC2cX3SmsdWsrRS8t_5zvzEw",
            ChannelIdResolver.extractChannelId("UC2cX3SmsdWsrRS8t_5zvzEw")
        )
    }

    @Test
    fun parsesChannelUrls() {
        assertEquals(
            "UC2cX3SmsdWsrRS8t_5zvzEw",
            ChannelIdResolver.extractChannelId("https://www.youtube.com/channel/UC2cX3SmsdWsrRS8t_5zvzEw")
        )
        assertEquals(
            "UC2cX3SmsdWsrRS8t_5zvzEw",
            ChannelIdResolver.extractChannelId("youtube.com/channel/UC2cX3SmsdWsrRS8t_5zvzEw")
        )
    }

    @Test
    fun recognizesHandles() {
        assertTrue(
            ChannelIdResolver.extractChannelId("@SafinaSociety") != null
        )
        assertTrue(
            ChannelIdResolver.extractChannelId("https://www.youtube.com/@SafinaSociety") != null
        )
    }

    @Test
    fun rejectsGarbage() {
        assertNull(ChannelIdResolver.extractChannelId(""))
        assertNull(ChannelIdResolver.extractChannelId("not a channel"))
        assertNull(ChannelIdResolver.extractChannelId("UCtooshort"))
    }
}
