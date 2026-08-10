package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.LiveStreamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamResolverTest {

    private val MAKKAH = "UCos52azQNBgW63_9uDJoPDA"
    private val MADINAH = "UCROKYPep-UuODNwyipe6JMw"

    // ── extractLiveVideoId: player-response live markers ────────────────

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
    fun `returns null when only ytInitialData carries an isLive marker but no player response`() {
        // Regression lock: extraction is scoped to the player response block.
        val html = """
            <script>var ytInitialData = {"contents":[{"videoRenderer":{"videoId":"AAAAAAAAAAA","isLive":true}}]};</script>
            <script>var ytInitialPlayerResponse = {};</script>
        """.trimIndent()
        assertNull(LiveStreamResolver.extractLiveVideoId(html))
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

    @Test
    fun `extracts videoId when player response has liveStreamability but no isLive flag`() {
        val html = """
            <script>
            var ytInitialPlayerResponse = {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{"videoId":"wawzF8i5yAo"},
              "liveStreamability":{"liveStreamabilityRenderer":{"videoId":"wawzF8i5yAo","pollDelayMs":"15000"}}
            };
            </script>
        """.trimIndent()
        assertEquals("wawzF8i5yAo", LiveStreamResolver.extractLiveVideoId(html))
    }

    @Test
    fun `extracts videoId when player response has liveBroadcastDetails with isLiveNow true`() {
        val html = """
            <script>
            var ytInitialPlayerResponse = {
              "videoDetails":{"videoId":"wawzF8i5yAo"},
              "playabilityStatus":{"status":"OK"},
              "liveBroadcastDetails":{"isLiveNow":true,"startTimestamp":"2026-08-09T00:00:00Z"}
            };
            </script>
        """.trimIndent()
        assertEquals("wawzF8i5yAo", LiveStreamResolver.extractLiveVideoId(html))
    }

    @Test
    fun `rejects an upcoming broadcast with liveBroadcastDetails isLiveNow false`() {
        val html = """
            <script>
            var ytInitialPlayerResponse = {
              "videoDetails":{"videoId":"wawzF8i5yAo"},
              "playabilityStatus":{"status":"OK"},
              "liveBroadcastDetails":{"isLiveNow":false,"startTimestamp":"2026-08-10T00:00:00Z"}
            };
            </script>
        """.trimIndent()
        assertNull(LiveStreamResolver.extractLiveVideoId(html))
    }

    @Test
    fun `returns null when player response has videoId but no live markers`() {
        val html = """
            <script>
            var ytInitialPlayerResponse = {
              "videoDetails":{"videoId":"BBBBBBBBBBB","isLive":false},
              "playabilityStatus":{"status":"OK"}
            };
            </script>
        """.trimIndent()
        assertNull(LiveStreamResolver.extractLiveVideoId(html))
    }

    // ── canonicalVideoId ────────────────────────────────────────────────

    @Test
    fun `canonicalVideoId extracts the watch id from a plain canonical link`() {
        val html = """
            <html><head>
            <link rel="canonical" href="https://www.youtube.com/watch?v=wawzF8i5yAo">
            </head><body></body></html>
        """.trimIndent()
        assertEquals("wawzF8i5yAo", LiveStreamResolver.canonicalVideoId(html))
    }

    @Test
    fun `canonicalVideoId extracts the watch id from an escaped canonical link`() {
        val html = """<link rel="canonical" href="https:\/\/www.youtube.com\/watch?v=wawzF8i5yAo">"""
        assertEquals("wawzF8i5yAo", LiveStreamResolver.canonicalVideoId(html))
    }

    @Test
    fun `canonicalVideoId returns null for a channel canonical link`() {
        val html = """<link rel="canonical" href="https://www.youtube.com/channel/UCos52azQNBgW63_9uDJoPDA">"""
        assertNull(LiveStreamResolver.canonicalVideoId(html))
    }

    // ── firstVideoIdInDoc ───────────────────────────────────────────────

    @Test
    fun `firstVideoIdInDoc returns the first videoId in the document`() {
        val html = """
            <script>var ytInitialData = {"contents":[{"videoRenderer":{"videoId":"wawzF8i5yAo"}}]};</script>
            <script>var ytInitialPlayerResponse = {};</script>
        """.trimIndent()
        assertEquals("wawzF8i5yAo", LiveStreamResolver.firstVideoIdInDoc(html))
    }

    @Test
    fun `firstVideoIdInDoc returns null when the document has no video ids`() {
        assertNull(LiveStreamResolver.firstVideoIdInDoc("<html>nothing here</html>"))
    }

    // ── Mandatory validation (live + own video + playable + owner channel) ─

    private fun liveWatchPage(channelId: String, status: String = "OK"): String = """
        <script>
        var ytInitialPlayerResponse = {
          "playabilityStatus":{"status":"$status"},
          "videoDetails":{"videoId":"wawzF8i5yAo","channelId":"$channelId","isLive":true},
          "liveStreamability":{}
        };
        </script>
    """.trimIndent()

    @Test
    fun `validates a live watch page of the expected channel`() {
        assertTrue(LiveStreamResolver.validateWatchPage(liveWatchPage(MAKKAH), "wawzF8i5yAo", MAKKAH))
    }

    @Test
    fun `rejects a watch page whose video is not live`() {
        val vod = """
            <script>
            var ytInitialPlayerResponse = {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{"videoId":"BBBBBBBBBBB","channelId":"$MAKKAH","isLive":false}
            };
            </script>
        """.trimIndent()
        assertFalse(LiveStreamResolver.validateWatchPage(vod, "BBBBBBBBBBB", MAKKAH))
    }

    @Test
    fun `rejects a watch page when playabilityStatus is not OK`() {
        // The Restricted-Mode signature: video present but status=ERROR.
        val blocked = """
            <script>
            var ytInitialPlayerResponse = {
              "playabilityStatus":{"status":"ERROR","reason":"This video is not available in Restricted Mode"},
              "videoDetails":{"videoId":"wawzF8i5yAo","channelId":"$MAKKAH","isLive":true}
            };
            </script>
        """.trimIndent()
        assertFalse(LiveStreamResolver.validateWatchPage(blocked, "wawzF8i5yAo", MAKKAH))
    }

    @Test
    fun `rejects a Madinah video for Makkah and vice versa`() {
        // Cross-channel substitution must be impossible.
        assertFalse(
            LiveStreamResolver.validateWatchPage(liveWatchPage(MADINAH), "wawzF8i5yAo", MAKKAH)
        )
        assertFalse(
            LiveStreamResolver.validateWatchPage(liveWatchPage(MAKKAH), "wawzF8i5yAo", MADINAH)
        )
    }

    @Test
    fun `accepts a live watch page when the owner channel cannot be determined`() {
        // Fail-open only when ownership is UNDETERMINABLE (the live + playable
        // checks still passed); a determinable mismatch always rejects.
        val html = """
            <script>
            var ytInitialPlayerResponse = {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{"videoId":"wawzF8i5yAo","isLive":true}
            };
            </script>
        """.trimIndent()
        assertTrue(LiveStreamResolver.validateWatchPage(html, "wawzF8i5yAo", MAKKAH))
    }

    // ── ownerChannelId / playabilityStatus ──────────────────────────────

    @Test
    fun `ownerChannelId reads the videoDetails channelId from the player response`() {
        assertEquals(MAKKAH, LiveStreamResolver.ownerChannelId(liveWatchPage(MAKKAH)))
    }

    @Test
    fun `ownerChannelId falls back to microformat ownerChannelId`() {
        val html = """
            <script>
            var ytInitialPlayerResponse = {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{"videoId":"wawzF8i5yAo","isLive":true},
              "microformat":{"playerMicroformatRenderer":{"ownerChannelId":"$MADINAH"}}
            };
            </script>
        """.trimIndent()
        assertEquals(MADINAH, LiveStreamResolver.ownerChannelId(html))
    }

    @Test
    fun `ownerChannelId returns null when ownership is not exposed`() {
        assertNull(LiveStreamResolver.ownerChannelId("<html>stripped</html>"))
    }

    @Test
    fun `playabilityStatus reads the status value`() {
        assertEquals("OK", LiveStreamResolver.playabilityStatus(liveWatchPage(MAKKAH)))
        assertNull(LiveStreamResolver.playabilityStatus("<html>no player response</html>"))
    }

    // ── Restricted-Mode / DNS-filter detection ─────────────────────────

    @Test
    fun `detects the restricted-mode signature - player response present, status ERROR, no live markers`() {
        // This is EXACTLY the page CleanBrowsing's Restricted-Mode IP serves:
        // ~795 KB, a player response whose playabilityStatus is ERROR, and no
        // live markers anywhere.
        val restricted = """
            <html><head><link rel="canonical" href="https://www.youtube.com/channel/UCos52azQNBgW63_9uDJoPDA"></head>
            <script>
            var ytInitialPlayerResponse = {
              "responseContext":{"serviceTrackingParams":[]},
              "playabilityStatus":{"status":"ERROR","reason":"Video unavailable"},
              "streamingData":{},
              "videoDetails":{"videoId":"wawzF8i5yAo"}
            };
            </script>
        """.trimIndent()
        assertTrue(LiveStreamResolver.looksRestrictedMode(restricted))
    }

    @Test
    fun `does not flag a full live page`() {
        assertFalse(LiveStreamResolver.looksRestrictedMode(liveWatchPage(MAKKAH)))
    }

    @Test
    fun `does not flag a page without a player response`() {
        // Channel-home redirect (no broadcast): no player response at all.
        val redirect = """
            <html><head><link rel="canonical" href="https://www.youtube.com/channel/UCos52azQNBgW63_9uDJoPDA"></head>
            <body><script>var ytInitialData = {};</script></body></html>
        """.trimIndent()
        assertFalse(LiveStreamResolver.looksRestrictedMode(redirect))
    }

    @Test
    fun `does not flag a non-ERROR playability failure`() {
        // Age/geo/login blocks show other statuses — only the exact ERROR
        // status with the reduced-page shape is treated as a filter block.
        val loginRequired = """
            <script>
            var ytInitialPlayerResponse = {
              "playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"},
              "videoDetails":{"videoId":"wawzF8i5yAo"}
            };
            </script>
        """.trimIndent()
        assertFalse(LiveStreamResolver.looksRestrictedMode(loginRequired))
    }

    @Test
    fun `does not flag a live page that keeps live markers despite an odd status`() {
        val odd = """
            <script>
            var ytInitialPlayerResponse = {
              "playabilityStatus":{"status":"LOGIN_REQUIRED"},
              "videoDetails":{"videoId":"wawzF8i5yAo","isLive":true}
            };
            </script>
        """.trimIndent()
        assertFalse(LiveStreamResolver.looksRestrictedMode(odd))
    }
}
