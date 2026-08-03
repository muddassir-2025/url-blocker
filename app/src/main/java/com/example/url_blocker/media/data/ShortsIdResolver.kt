package com.example.url_blocker.media.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves which videos of a channel are Shorts by scraping the channel's
 * `/shorts` tab — the same key-less technique as [ChannelIdResolver] /
 * [LiveStreamResolver] / [ChannelAvatarResolver].
 *
 * The RSS feed has no duration and many Shorts omit the `#shorts` hashtag, so
 * title-based detection alone leaves the Shorts row mostly empty. YouTube's
 * channel `/shorts` tab server-renders `ytInitialData` listing ONLY that
 * channel's Shorts, so every 11-char video id found on the page belongs to a
 * Short. [extractShortsIds] is a pure function (unit-testable); [fetchShortsIds]
 * does the network call. Failures return an empty set — the feed then falls
 * back to the `#shorts` hashtag signal only.
 */
object ShortsIdResolver {

    private const val TAG = "ShortsIdResolver"

    // Short-lived in-memory cache (same pattern as LiveStreamResolver) so the
    // Media tab doesn't re-scrape every channel's /shorts tab on every open.
    // Only non-empty results are cached — a channel that gains Shorts later is
    // picked up on the next refresh.
    private const val CACHE_TTL_MS = 10 * 60_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Set<String>, Long>>()

    private val VIDEO_ID = Regex("\"videoId\"\\s*:\\s*\"([0-9A-Za-z_-]{11})\"")

    /** Pure extraction step: all 11-char video ids on the /shorts tab page. */
    fun extractShortsIds(html: String): Set<String> {
        // On the /shorts tab virtually every videoId belongs to a Short of this
        // channel. Collecting all of them is intentionally permissive — a stray
        // recommended id is harmless, while a missed id would hide a Short.
        return VIDEO_ID.findAll(html).map { it.groupValues[1] }.toSet()
    }

    /**
     * Fetches the channel's /shorts tab and returns its video ids (empty set on
     * any failure — the caller degrades to hashtag-only detection). A DESKTOP
     * UA is required (a mobile UA serves a reduced page without ytInitialData).
     */
    suspend fun fetchShortsIds(channelId: String): Set<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache[channelId]?.let { (ids, at) ->
            if (now - at < CACHE_TTL_MS) {
                Log.d(TAG, "SHORTS_CACHE_HIT channelId=$channelId count=${ids.size}")
                return@withContext ids
            }
        }
        val url = "https://www.youtube.com/channel/$channelId/shorts"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
                )
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext emptySet()
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val ids = extractShortsIds(html)
            if (ids.isNotEmpty()) cache[channelId] = ids to now
            Log.d(TAG, "SHORTS_RESOLVED channelId=$channelId count=${ids.size}")
            ids
        } catch (e: Exception) {
            Log.w(TAG, "SHORTS_FETCH_FAILED channelId=$channelId: ${e.message}")
            emptySet()
        } finally {
            connection?.disconnect()
        }
    }
}
