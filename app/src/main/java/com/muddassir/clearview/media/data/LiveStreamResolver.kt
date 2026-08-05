package com.muddassir.clearview.media.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves a YouTube channel's CURRENT live broadcast video id so the Live tab
 * can play it with the same in-app IFrame player as regular videos.
 *
 * YouTube blocks its channel-based live embed (`/embed/live_stream?channel=…`,
 * error 153) and the Saudi CDN HLS mirrors are unreliable, so the current live
 * broadcast is resolved at runtime from the channel's `/live` page: the page
 * embeds a `ytInitialPlayerResponse` JSON whose `videoDetails.videoId` is the
 * broadcast currently airing, marked `"isLive":true`. When nothing is live the
 * page has no live player response — the resolver returns null and the tab
 * shows "Live stream currently unavailable".
 *
 * Verified: both target channels currently air live broadcasts that resolve
 * correctly (Makkah → wawzF8i5yAo, Madinah → Rs7St51oDDc) when fetched with a
 * DESKTOP user agent — a mobile UA makes YouTube serve a page without the
 * player response. The same technique (regex over server-rendered page JSON)
 * is already used by [ChannelIdResolver], so no API key is needed.
 */
object LiveStreamResolver {

    private const val TAG = "LiveStreamResolver"
    private const val CACHE_TTL_MS = 60_000L

    private val VIDEO_ID = Regex("\"videoId\"\\s*:\\s*\"([0-9A-Za-z_-]{11})\"")
    // A genuinely-airing broadcast marks itself live in the player response.
    private val IS_LIVE = Regex("\"isLive\"\\s*:\\s*true|\"isLiveNow\"\\s*:\\s*true")

    // channelId -> (videoId, resolvedAt). Only successful resolutions are
    // cached (short TTL); a retry (forceRefresh) always re-fetches so a newly
    // started broadcast is picked up immediately. ConcurrentHashMap because
    // the singleton is shared across IO threads.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Returns the channel's current live broadcast video id, or null when no
     * broadcast is airing or the page can't be fetched. [forceRefresh] bypasses
     * the short-lived cache (used by the Retry button).
     */
    suspend fun resolveLiveVideoId(
        channelId: String,
        forceRefresh: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cache[channelId]?.let { (id, at) ->
                if (now - at < CACHE_TTL_MS) {
                    Log.d(TAG, "LIVE_CACHE_HIT channelId=$channelId videoId=$id")
                    return@withContext id
                }
            }
        }
        val html = fetchLivePage(channelId)
        if (html == null) {
            Log.w(TAG, "LIVE_FETCH_NULL channelId=$channelId")
            return@withContext null
        }
        // DIAGNOSTIC: what did the scrape actually return? Presence flags tell
        // us whether the page shape changed (consent/bot page, redirect) vs.
        // the channel genuinely having no active broadcast.
        val hasPlayerResponse = html.contains("ytInitialPlayerResponse")
        val hasIsLiveMarker = IS_LIVE.containsMatchIn(html)
        Log.d(
            TAG,
            "LIVE_PAGE channelId=$channelId len=${html.length} " +
                "hasPlayerResponse=$hasPlayerResponse hasIsLiveMarker=$hasIsLiveMarker " +
                "prefix=${html.take(140).replace('\n', ' ')}"
        )
        val id = extractLiveVideoId(html)
        if (id != null) {
            cache[channelId] = id to now
            Log.d(TAG, "LIVE_RESOLVED channelId=$channelId videoId=$id")
            id
        } else {
            Log.w(TAG, "LIVE_NOT_ACTIVE channelId=$channelId (hasPlayerResponse=$hasPlayerResponse)")
            null
        }
    }

    private fun fetchLivePage(channelId: String): String? {
        val url = "https://www.youtube.com/channel/$channelId/live"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                // CRITICAL: a MOBILE UA makes YouTube serve a reduced page that
                // omits the live player response entirely (no videoId → the
                // resolver wrongly reports LIVE_NOT_ACTIVE). A DESKTOP UA gets
                // the full page with ytInitialPlayerResponse.videoDetails.
                setRequestProperty("Accept", "text/html")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
                )
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "LIVE_FETCH_FAILED channelId=$channelId: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Pure extraction step (unit-testable): live video id from the page HTML. */
    fun extractLiveVideoId(html: String): String? {
        // Scope to the ytInitialPlayerResponse block so related-video ids from
        // other parts of the page can't be picked up.
        val start = html.indexOf("ytInitialPlayerResponse")
        val block = if (start >= 0) {
            val end = html.indexOf("</script>", start)
            if (end > start) html.substring(start, end) else html.substring(start)
        } else {
            html
        }
        // Only report broadcasts that are genuinely live right now.
        if (!IS_LIVE.containsMatchIn(block)) return null
        return VIDEO_ID.find(block)?.groupValues?.get(1)
    }
}
