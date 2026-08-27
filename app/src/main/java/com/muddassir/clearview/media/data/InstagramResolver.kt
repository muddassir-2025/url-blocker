package com.muddassir.clearview.media.data

import android.util.Log
import com.muddassir.clearview.backend.ClearViewBackendClient
import com.muddassir.clearview.media.model.MediaVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves Instagram public profiles and fetches recent timeline media.
 *
 * Uses a prioritized list of [InstagramSource] providers:
 * 1. DirectRssInstagramSource — for explicit RSS feed URLs (RSS.app, etc.)
 * 2. BackendInstagramSource — server-side proxy
 * 3. PublicRssBridgeInstagramSource — public RSS-Bridge instances
 * 4. WebProfileInstagramSource — direct client-side scraper (fallback)
 *
 * If ALL sources fail, returns null so the caller can show an error.
 * No dummy/fallback posts are ever generated.
 */
object InstagramResolver {

    private const val TAG = "InstagramResolver"

    private val sources: List<InstagramSource>
        get() = listOf(
            DirectRssInstagramSource(),
            BackendInstagramSource(ClearViewBackendClient.baseUrl),
            PublicRssBridgeInstagramSource(),
            WebProfileInstagramSource()
        )

    data class InstagramProfile(
        val username: String,
        val fullName: String,
        val avatarUrl: String?,
        val posts: List<MediaVideo>
    )

    /**
     * Extracts a clean Instagram username or detects an RSS feed URL.
     * Returns null if the input doesn't look like an Instagram handle or RSS feed.
     */
    fun extractUsername(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // Direct RSS feed URL support (RSS.app / RSS-Bridge / generic RSS XML)
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            if (trimmed.contains("rss.app/feeds/") ||
                trimmed.contains("bridge=Instagram") ||
                trimmed.endsWith(".xml") ||
                trimmed.contains("/rss") ||
                trimmed.contains("/feed")
            ) {
                return trimmed
            }
            if (trimmed.contains("instagram.com/") || trimmed.contains("instagr.am/")) {
                val after = trimmed.substringAfter("instagram.com/").substringAfter("instagr.am/")
                val user = after.substringBefore('/').substringBefore('?').substringBefore('#').trim()
                if (user.isNotEmpty() && user != "p" && user != "reel" && user != "stories") {
                    return user.removePrefix("@")
                }
            }
        }

        val clean = trimmed.removePrefix("@").trim()
        if (clean.matches(Regex("^[a-zA-Z0-9._]{1,30}$")) && !clean.startsWith("UC")) {
            return clean
        }
        return null
    }

    /**
     * Attempts to resolve an Instagram profile for [input].
     * Returns null if the profile cannot be found or all sources fail.
     * Never generates dummy/fallback posts.
     */
    suspend fun resolve(input: String): InstagramProfile? = withContext(Dispatchers.IO) {
        val usernameOrUrl = extractUsername(input) ?: return@withContext null
        fetchProfile(usernameOrUrl)
    }

    /**
     * Fetches public profile metadata and timeline media using registered
     * InstagramSource providers. Tries each source in order; returns the
     * first successful result. Returns null if all sources fail.
     */
    suspend fun fetchProfile(usernameOrUrl: String): InstagramProfile? = withContext(Dispatchers.IO) {
        val cleanInput = usernameOrUrl.trim()

        for (source in sources) {
            try {
                Log.d(TAG, "Trying source ${source.name} for $cleanInput")
                when (val result = source.fetchProfile(cleanInput)) {
                    is InstagramFeedResult.Success -> {
                        Log.d(TAG, "Source ${source.name} returned ${result.items.size} items for $cleanInput")
                        return@withContext InstagramProfile(
                            username = result.username,
                            fullName = result.fullName,
                            avatarUrl = result.avatarUrl,
                            posts = result.items
                        )
                    }
                    is InstagramFeedResult.Error -> {
                        Log.d(TAG, "Source ${source.name} failed for $cleanInput: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Source ${source.name} exception: ${e.message}")
            }
        }

        Log.w(TAG, "All sources failed for $cleanInput")
        null
    }
}
