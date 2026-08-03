package com.example.url_blocker.media

import com.example.url_blocker.media.data.LiveStreamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveStreamResolverTest {

    @Test
    fun `extracts live videoId from player response when broadcast is live`() {
        val html = """
            <html><head><script>
            var ytInitialPlayerResponse = {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"wawzF8i5yAo","isLive":true}};
            </script></head></html>
        """.trimIndent()
        assertEquals("wawzF8i5yAo", LiveStreamResolver.extractLiveVideoId(html))
    }

    @Test
    fun `returns null when the broadcast is not live`() {
        val html = """
            <script>
            var ytInitialPlayerResponse = {"videoDetails":{"videoId":"wawzF8i5yAo","isLive":false}};
            </script>
        """.trimIndent()
        assertNull(LiveStreamResolver.extractLiveVideoId(html))
    }

    @Test
    fun `returns null when there is no player response at all`() {
        assertNull(LiveStreamResolver.extractLiveVideoId("<html>no live stream here</html>"))
    }

    @Test
    fun `ignores related video ids outside the player response block`() {
        val html = """
            <script>var ytInitialData = {"contents":[{"renderedContent":{"videoId":"AAAAAAAAAAA"}}]};</script>
            <script>
            var ytInitialPlayerResponse = {"videoDetails":{"videoId":"wawzF8i5yAo","isLive":true}};
            </script>
        """.trimIndent()
        assertEquals("wawzF8i5yAo", LiveStreamResolver.extractLiveVideoId(html))
    }
}
