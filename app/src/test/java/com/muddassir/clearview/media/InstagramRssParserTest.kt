package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.InstagramRssParser
import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.media.model.MediaPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramRssParserTest {

    private val sampleRss2Feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel>
            <title>Instagram - maherzainofficial</title>
            <link>https://www.instagram.com/maherzainofficial</link>
            <description>Maher Zain's official Instagram posts</description>
            <image>
              <url>https://cdn.example.com/avatar.jpg</url>
              <title>maherzainofficial</title>
              <link>https://www.instagram.com/maherzainofficial</link>
            </image>
            <item>
              <title>Peace be upon you all! #ramadan</title>
              <link>https://www.instagram.com/reel/C_abc123/</link>
              <guid isPermaLink="true">https://www.instagram.com/reel/C_abc123/</guid>
              <pubDate>Mon, 26 Aug 2024 14:00:00 GMT</pubDate>
              <description><![CDATA[<img src="https://cdn.example.com/reel_thumb.jpg" /><p>Peace be upon you all! #ramadan</p>]]></description>
              <enclosure url="https://cdn.example.com/reel_video.mp4" type="video/mp4" length="12345" />
            </item>
            <item>
              <title>Beautiful photo from the tour</title>
              <link>https://www.instagram.com/p/C_def456/</link>
              <guid isPermaLink="true">https://www.instagram.com/p/C_def456/</guid>
              <pubDate>Sun, 25 Aug 2024 12:00:00 GMT</pubDate>
              <description><![CDATA[<img src="https://cdn.example.com/photo1.jpg" /><p>Beautiful photo from the tour</p>]]></description>
            </item>
            <item>
              <title>Tour memories carousel</title>
              <link>https://www.instagram.com/p/C_ghi789/</link>
              <guid isPermaLink="true">https://www.instagram.com/p/C_ghi789/</guid>
              <pubDate>Sat, 24 Aug 2024 10:00:00 GMT</pubDate>
              <description><![CDATA[<img src="https://cdn.example.com/car1.jpg" /><img src="https://cdn.example.com/car2.jpg" /><p>Tour memories carousel</p>]]></description>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private val sampleAtomFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>maherzainofficial - Instagram Bridge</title>
          <link rel="alternate" type="text/html" href="https://www.instagram.com/maherzainofficial"/>
          <logo>https://cdn.example.com/avatar.jpg</logo>
          <entry>
            <title>New song release preview</title>
            <link rel="alternate" type="text/html" href="https://www.instagram.com/reel/C_jkl012/"/>
            <id>https://www.instagram.com/reel/C_jkl012/</id>
            <published>2024-08-20T16:00:00Z</published>
            <content type="html"><![CDATA[<img src="https://cdn.example.com/atom_reel.jpg" /><p>New song release preview</p>]]></content>
          </entry>
          <entry>
            <title>Studio vibes</title>
            <link rel="alternate" type="text/html" href="https://www.instagram.com/p/C_mno345/"/>
            <id>https://www.instagram.com/p/C_mno345/</id>
            <published>2024-08-19T10:00:00Z</published>
            <content type="html"><![CDATA[<img src="https://cdn.example.com/atom_photo.jpg" /><p>Studio vibes</p>]]></content>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parsesRss2FeedCorrectly() {
        val result = InstagramRssParser.parse(sampleRss2Feed, "maherzainofficial")
        assertNotNull(result)
        assertEquals("maherzainofficial", result!!.username)
        assertEquals("https://cdn.example.com/avatar.jpg", result.avatarUrl)
        assertEquals(3, result.items.size)

        // Item 1: Reel
        val reel = result.items[0]
        assertEquals("ig_C_abc123", reel.videoId)
        assertEquals(MediaPlatform.INSTAGRAM, reel.platform)
        assertEquals(InstagramMediaType.REEL, reel.instagramType)
        assertTrue(reel.isShort)
        assertEquals("https://cdn.example.com/reel_video.mp4", reel.mediaUrl)
        assertEquals("https://cdn.example.com/reel_thumb.jpg", reel.thumbnailUrl)
        assertEquals("https://www.instagram.com/reel/C_abc123/", reel.instagramUrl)

        // Item 2: Image Post
        val imagePost = result.items[1]
        assertEquals("ig_C_def456", imagePost.videoId)
        assertEquals(InstagramMediaType.IMAGE, imagePost.instagramType)
        assertNull(imagePost.mediaUrl) // No video URL for image post
        assertEquals("https://cdn.example.com/photo1.jpg", imagePost.thumbnailUrl)

        // Item 3: Carousel
        val carousel = result.items[2]
        assertEquals("ig_C_ghi789", carousel.videoId)
        assertEquals(InstagramMediaType.CAROUSEL, carousel.instagramType)
        assertEquals("https://cdn.example.com/car1.jpg", carousel.thumbnailUrl)
    }

    @Test
    fun parsesAtomFeedCorrectly() {
        val result = InstagramRssParser.parse(sampleAtomFeed, "maherzainofficial")
        assertNotNull(result)
        assertEquals("maherzainofficial", result!!.username)
        assertEquals("https://cdn.example.com/avatar.jpg", result.avatarUrl)
        assertEquals(2, result.items.size)

        val reel = result.items[0]
        assertEquals("ig_C_jkl012", reel.videoId)
        assertEquals(InstagramMediaType.REEL, reel.instagramType)

        val photo = result.items[1]
        assertEquals("ig_C_mno345", photo.videoId)
        assertEquals(InstagramMediaType.IMAGE, photo.instagramType)
        assertEquals("https://cdn.example.com/atom_photo.jpg", photo.thumbnailUrl)
    }

    @Test
    fun handlesEmptyOrCorruptXmlDefensively() {
        val emptyResult = InstagramRssParser.parse("", "maherzainofficial")
        assertNull(emptyResult)

        val garbageResult = InstagramRssParser.parse("<not>xml", "maherzainofficial")
        assertNull(garbageResult)
    }
}
