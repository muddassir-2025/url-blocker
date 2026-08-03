package com.muddassir.clearview.media.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves a YouTube channel's avatar image URL from its channel page — the
 * same key-less scraping technique as [ChannelIdResolver] and
 * [LiveStreamResolver].
 *
 * The channel page embeds the avatar inside its `ytInitialData` JSON as
 * `"avatar":{"thumbnails":[{"url":"https://yt3.ggpht.com/…"}]}`. [extractAvatarUrl]
 * is a pure function (unit-testable); [fetchAvatar] does the network call.
 * When the fetch fails (bot/consent page, offline) the caller keeps the
 * channel's avatar as null and the UI falls back to initials.
 */
object ChannelAvatarResolver {

    private const val TAG = "ChannelAvatarResolver"

    // "avatar":{"thumbnails":[{"url":"https:\/\/yt3.ggpht.com\/ytc\/...","width":88,"height":88}]
    // The JSON escapes slashes (\/); the URL value itself never contains an
    // unescaped quote, so a lazy match to the first quote captures it exactly.
    private val AVATAR_REGEX = Regex(
        "\"avatar\"\\s*:\\s*\\{\\s*\"thumbnails\"\\s*:\\s*\\[\\s*\\{\\s*\"url\"\\s*:\\s*\"([^\"]+)\"",
        RegexOption.DOT_MATCHES_ALL
    )

    // Fallback: channelThumbnailWithAvatarFallbackRenderer (some page variants).
    private val FALLBACK_REGEX = Regex(
        "\"channelThumbnailWithAvatarFallbackRenderer\"\\s*:\\s*\\{\\s*\"thumbnail\"\\s*:\\s*\\{\\s*\"thumbnails\"" +
            "\\s*:\\s*\\[\\s*\\{\\s*\"url\"\\s*:\\s*\"([^\"]+)\"",
        RegexOption.DOT_MATCHES_ALL
    )

    /** Pure extraction step: avatar URL from the raw channel page HTML, or null. */
    fun extractAvatarUrl(html: String): String? {
        val raw = (AVATAR_REGEX.find(html) ?: FALLBACK_REGEX.find(html))?.groupValues?.get(1)
            ?: return null
        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
    }

    /**
     * Fetches the channel page and extracts the avatar URL. A DESKTOP UA is
     * required — a mobile UA makes YouTube serve a reduced page that omits the
     * avatar JSON (same caveat as the live-stream resolver).
     */
    suspend fun fetchAvatar(channelId: String): String? = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/channel/$channelId"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
                )
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val avatar = extractAvatarUrl(html)
            if (avatar != null) {
                Log.d(TAG, "AVATAR_RESOLVED channelId=$channelId")
            } else {
                Log.w(TAG, "AVATAR_NOT_FOUND channelId=$channelId")
            }
            avatar
        } catch (e: Exception) {
            Log.w(TAG, "AVATAR_FETCH_FAILED channelId=$channelId: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
