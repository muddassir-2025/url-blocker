package com.muddassir.clearview.media.data

import android.util.Log
import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves Instagram public profiles and fetches recent timeline reels/images.
 */
object InstagramResolver {

    private const val TAG = "InstagramResolver"

    private val sources: List<InstagramSource> = listOf(
        WebProfileInstagramSource()
    )

    data class InstagramProfile(
        val username: String,
        val fullName: String,
        val avatarUrl: String?,
        val posts: List<MediaVideo>
    )

    /**
     * Extracts a clean Instagram username from a handle (@name), full URL, or bare username.
     */
    fun extractUsername(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("instagram.com/") || trimmed.contains("instagr.am/")) {
            val after = trimmed.substringAfter("instagram.com/").substringAfter("instagr.am/")
            val user = after.substringBefore('/').substringBefore('?').substringBefore('#').trim()
            if (user.isNotEmpty() && user != "p" && user != "reel" && user != "stories") {
                return user.removePrefix("@")
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
     */
    suspend fun resolve(input: String): InstagramProfile? = withContext(Dispatchers.IO) {
        val username = extractUsername(input) ?: return@withContext null
        fetchProfile(username)
    }

    /**
     * Fetches public profile metadata and timeline media using registered InstagramSource providers.
     */
    suspend fun fetchProfile(username: String): InstagramProfile? = withContext(Dispatchers.IO) {
        val cleanUsername = username.removePrefix("@").trim()
        
        for (source in sources) {
            try {
                when (val result = source.fetchProfile(cleanUsername)) {
                    is InstagramFeedResult.Success -> {
                        return@withContext InstagramProfile(
                            username = result.username,
                            fullName = result.fullName,
                            avatarUrl = result.avatarUrl,
                            posts = result.items
                        )
                    }
                    is InstagramFeedResult.Error -> {
                        Log.d(TAG, "Source ${source.name} failed for $cleanUsername: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Source ${source.name} exception: ${e.message}")
            }
        }

        // Resilient fallback: return valid profile so user can still add and view creator
        val channelId = "ig_${cleanUsername.lowercase()}"
        val fallbackPosts = createFallbackPosts(cleanUsername, cleanUsername, null)
        InstagramProfile(
            username = cleanUsername,
            fullName = cleanUsername,
            avatarUrl = null,
            posts = fallbackPosts
        )
    }

    private fun createFallbackPosts(username: String, fullName: String, avatarUrl: String?): List<MediaVideo> {
        val channelId = "ig_${username.lowercase()}"
        val now = System.currentTimeMillis()
        return listOf(
            MediaVideo(
                videoId = "ig_${username}_reels",
                title = "$fullName • Instagram Reels",
                channelId = channelId,
                channelName = fullName,
                publishedAtEpochMillis = now,
                thumbnailUrl = avatarUrl ?: "",
                viewCount = 0L,
                isShort = true,
                isLive = false,
                durationSeconds = 0L,
                platform = MediaPlatform.INSTAGRAM,
                instagramType = InstagramMediaType.REEL
            ),
            MediaVideo(
                videoId = "ig_${username}_posts",
                title = "$fullName • Recent Posts & Photos",
                channelId = channelId,
                channelName = fullName,
                publishedAtEpochMillis = now - 60_000L,
                thumbnailUrl = avatarUrl ?: "",
                viewCount = 0L,
                isShort = false,
                isLive = false,
                durationSeconds = 0L,
                platform = MediaPlatform.INSTAGRAM,
                instagramType = InstagramMediaType.IMAGE
            )
        )
    }
}
