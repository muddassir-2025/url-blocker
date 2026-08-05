package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.VideoDurationResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure duration-extraction logic in [VideoDurationResolver]. The
 * network path ([VideoDurationResolver.fetchDuration]) is deliberately not
 * exercised here — the watch page can change, so extraction from a realistic
 * HTML sample is what's stable and worth locking in.
 */
class VideoDurationResolverTest {

    /** A realistic snippet of a watch page's ytInitialPlayerResponse. */
    private fun watchPage(lengthSeconds: String) = """
        <html><body>
        <script>var ytc = { "web_client_version": "2026" };</script>
        <script>var ytInitialPlayerResponse = {
            "playabilityStatus": {"status": "OK"},
            "videoDetails": {
                "videoId": "abc123XYZ",
                "lengthSeconds": "$lengthSeconds",
                "title": "Example"
            },
            "streamingData": {"expiresInSeconds": "21600"}
        };</script>
        <script>var ytInitialData = {};</script>
        </body></html>
    """.trimIndent()

    @Test
    fun `extracts lengthSeconds from ytInitialPlayerResponse`() {
        assertEquals(7542L, VideoDurationResolver.extractDurationSeconds(watchPage("7542")))
    }

    @Test
    fun `extracts a one-hour duration`() {
        assertEquals(3600L, VideoDurationResolver.extractDurationSeconds(watchPage("3600")))
    }

    @Test
    fun `extracts an unquoted lengthSeconds value`() {
        val html = """
            <script>var ytInitialPlayerResponse = {
                "videoDetails": { "videoId": "x", "lengthSeconds": 540 }
            };</script>
        """.trimIndent()
        assertEquals(540L, VideoDurationResolver.extractDurationSeconds(html))
    }

    @Test
    fun `returns null when player response is missing`() {
        assertNull(
            VideoDurationResolver.extractDurationSeconds(
                "<html><body><script>var ytInitialData = {}</script></body></html>"
            )
        )
    }

    @Test
    fun `returns null for empty or malformed html`() {
        assertNull(VideoDurationResolver.extractDurationSeconds(""))
        assertNull(VideoDurationResolver.extractDurationSeconds("not html at all"))
    }

    @Test
    fun `returns null when lengthSeconds is not a number`() {
        assertNull(
            VideoDurationResolver.extractDurationSeconds(watchPage("NaN"))
        )
    }

    @Test
    fun `scopes to the player response block only`() {
        // A lengthSeconds from a related video's player response on the same
        // page must not be picked up.
        val html = """
            <script>var ytInitialPlayerResponse = {"videoDetails": {"lengthSeconds": "123"}};</script>
            <script>var ytInitialPlayerResponse = {"videoDetails": {"lengthSeconds": "999999"}};</script>
        """.trimIndent()
        // The first occurrence's block is used.
        assertEquals(123L, VideoDurationResolver.extractDurationSeconds(html))
    }

    // ── Disk cache (encode/decode) ────────────────────────────────

    @Test
    fun `disk cache encode decode round-trips all entries`() {
        val entries = mapOf(
            "abc123" to (7542L to 1000L),
            "def456" to (0L to 2000L) // failure marker
        )
        val decoded = VideoDurationResolver.decodeDiskCache(
            VideoDurationResolver.encodeDiskCache(entries),
            now = 10_000L
        )
        assertEquals(entries, decoded)
    }

    @Test
    fun `disk cache decode keeps fresh successes and drops stale ones`() {
        val now = System.currentTimeMillis()
        val fresh = "fresh1" to (3600L to now - 1000L)
        val stale = "stale1" to (3600L to now - VideoDurationResolver.SUCCESS_TTL_MS - 1L)
        val decoded = VideoDurationResolver.decodeDiskCache(
            VideoDurationResolver.encodeDiskCache(mapOf(fresh, stale)),
            now = now
        )
        assertEquals(setOf("fresh1"), decoded.keys)
        assertEquals(3600L, decoded["fresh1"]?.first)
    }

    @Test
    fun `disk cache decode drops stale failures after negative ttl`() {
        val now = System.currentTimeMillis()
        val fresh = "freshNeg" to (0L to now - 1000L)
        val stale = "staleNeg" to (0L to now - VideoDurationResolver.NEGATIVE_TTL_MS - 1L)
        val decoded = VideoDurationResolver.decodeDiskCache(
            VideoDurationResolver.encodeDiskCache(mapOf(fresh, stale)),
            now = now
        )
        assertEquals(setOf("freshNeg"), decoded.keys)
    }

    @Test
    fun `disk cache decode ignores blank video ids and corrupt input`() {
        // Blank video id entry.
        val withBlank = """{"entries":[{"videoId":"","seconds":60,"at":1}]}"""
        assertTrue(VideoDurationResolver.decodeDiskCache(withBlank, now = 1000L).isEmpty())

        // Corrupt / blank input → empty.
        assertTrue(VideoDurationResolver.decodeDiskCache(null, now = 1000L).isEmpty())
        assertTrue(VideoDurationResolver.decodeDiskCache("", now = 1000L).isEmpty())
        assertTrue(VideoDurationResolver.decodeDiskCache("not json", now = 1000L).isEmpty())
    }
}
