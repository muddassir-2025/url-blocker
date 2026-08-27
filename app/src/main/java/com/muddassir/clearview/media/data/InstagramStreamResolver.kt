package com.muddassir.clearview.media.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves direct playable .mp4 video stream URLs for public Instagram posts and Reels
 * completely on-device without login, cookies, or account sessions.
 *
 * Uses Instagram's public embed endpoints (which Meta maintains for public web embedding).
 */
object InstagramStreamResolver {

    private const val TAG = "InstagramStreamResolver"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 12_000

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Resolves the direct .mp4 media stream URL for [shortcodeOrUrl].
     * Returns null if the post is an image post or resolution fails.
     */
    suspend fun resolveStreamUrl(shortcodeOrUrl: String): String? = withContext(Dispatchers.IO) {
        val shortcode = extractShortcode(shortcodeOrUrl)
        if (shortcode.isBlank()) return@withContext null

        val candidateUrls = listOf(
            "https://www.instagram.com/p/$shortcode/embed/captioned/",
            "https://www.instagram.com/reel/$shortcode/embed/captioned/"
        )

        for (candidateUrl in candidateUrls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(candidateUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

                    // Pattern 1: Embedded JSON "video_url":"https:\/\/..."
                    val jsonVideoRegex = Regex(""""video_url"\s*:\s*"([^"]+)"""")
                    val matchJson = jsonVideoRegex.find(html)?.groupValues?.get(1)
                    if (!matchJson.isNullOrBlank()) {
                        val clean = unescapeUrl(matchJson)
                        if (clean.contains(".mp4") || clean.startsWith("http")) {
                            Log.d(TAG, "Found video_url in embed JSON for $shortcode")
                            return@withContext clean
                        }
                    }

                    // Pattern 2: HTML5 <video ... src="..."
                    val videoTagRegex = Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    val matchTag = videoTagRegex.find(html)?.groupValues?.get(1)
                    if (!matchTag.isNullOrBlank()) {
                        val clean = unescapeUrl(matchTag)
                        if (clean.contains(".mp4") || clean.startsWith("http")) {
                            Log.d(TAG, "Found <video src> for $shortcode")
                            return@withContext clean
                        }
                    }

                    // Pattern 3: Any direct .mp4 CDN link in the payload
                    val rawMp4Regex = Regex("""https:\\/\\/[^"'\s\\]+?\.mp4[^"'\s\\]*""")
                    val matchRaw = rawMp4Regex.find(html)?.value
                    if (!matchRaw.isNullOrBlank()) {
                        val clean = unescapeUrl(matchRaw)
                        Log.d(TAG, "Found raw .mp4 link for $shortcode")
                        return@withContext clean
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed resolving stream for $shortcode from $candidateUrl: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }

        null
    }

    fun extractShortcode(input: String): String {
        val trimmed = input.trim().removePrefix("ig_")
        if (!trimmed.contains("/")) {
            // Already a shortcode (e.g. C_abc123)
            return trimmed.substringBefore('?').substringBefore('#')
        }
        val patterns = listOf(
            Regex("""instagram\.com/(?:p|reel|tv)/([^/?#&]+)"""),
            Regex("""instagr\.am/(?:p|reel|tv)/([^/?#&]+)""")
        )
        for (p in patterns) {
            val match = p.find(trimmed)
            if (match != null) return match.groupValues[1]
        }
        return trimmed.substringAfterLast('/').substringBefore('?').substringBefore('#')
    }

    private fun unescapeUrl(raw: String): String {
        return raw.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
            .trim()
    }
}
