package com.muddassir.clearview.media.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves a user-provided channel reference to a YouTube channel id (UC…):
 *  - bare channel ids ("UC2cX3SmsdWsrRS8t_5zvzEw") are accepted as-is,
 *  - `/channel/UC…` (and `/c/` `/user/`) URLs are parsed directly,
 *  - `@handles` are resolved by fetching the channel page and extracting the
 *    channel id from the embedded `"channelId":"UC…"` JSON (no API key needed).
 *
 * [extractChannelId] is a pure function (no network) and is unit-testable.
 */
object ChannelIdResolver {

    private const val TAG = "ChannelIdResolver"

    private val BARE_ID = Regex("^UC[0-9A-Za-z_-]{22}$")
    private val CHANNEL_PATH = Regex("(?:youtube\\.com|youtu\\.be)/(?:channel|c)/(UC[0-9A-Za-z_-]{22})")
    private val HANDLE = Regex("@([0-9A-Za-z._-]+)")

    /**
     * Pure extraction step. Returns:
     *  - the UC… id when [input] is a channel URL or bare id,
     *  - the normalized handle ("@Name") when [input] is a handle,
     *  - null when [input] looks like neither (callers surface this as an
     *    "invalid channel" error).
     */
    fun extractChannelId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        BARE_ID.find(trimmed)?.let { return it.value }
        CHANNEL_PATH.find(trimmed)?.let { return it.groupValues[1] }
        // The regex match value already includes the "@" ("@SafinaSociety"),
        // which is exactly what resolveHandle() needs for the fetch URL.
        HANDLE.find(trimmed)?.let { return it.value }
        return null
    }

    /**
     * Full resolution: returns a UC… id, or null when the input is invalid or
     * the handle page can't be fetched/parsed.
     */
    suspend fun resolve(input: String): String? {
        val extracted = extractChannelId(input) ?: return null
        return if (BARE_ID.matches(extracted)) {
            extracted
        } else {
            resolveHandle(extracted)
        }
    }

    private suspend fun resolveHandle(handle: String): String? = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/$handle"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // ytInitialData embeds "channelId":"UC…"; meta itemprop also works.
            val jsonMatch = Regex("\"channelId\"\\s*:\\s*\"(UC[0-9A-Za-z_-]{22})\"").find(html)
            val metaMatch = Regex("<meta\\s+itemprop=\"identifier\"\\s+content=\"(UC[0-9A-Za-z_-]{22})\"").find(html)
            (jsonMatch?.groupValues?.get(1) ?: metaMatch?.groupValues?.get(1))
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
