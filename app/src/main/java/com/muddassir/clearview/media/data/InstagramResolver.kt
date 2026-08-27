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
     * Fetches public profile metadata and timeline media using Instagram's web profile endpoint.
     */
    suspend fun fetchProfile(username: String): InstagramProfile? = withContext(Dispatchers.IO) {
        val cleanUsername = username.removePrefix("@").trim()
        val url = "https://i.instagram.com/api/v1/users/web_profile_info/?username=$cleanUsername"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("X-IG-App-ID", "936619743392459")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "fetchProfile response code: ${connection.responseCode} for $cleanUsername")
                // Return basic fallback profile so user can still add the channel
                return@withContext InstagramProfile(
                    username = cleanUsername,
                    fullName = cleanUsername,
                    avatarUrl = null,
                    posts = emptyList()
                )
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(body)
            val user = root.optJSONObject("data")?.optJSONObject("user") ?: return@withContext null
            val fullName = user.optString("full_name").ifBlank { cleanUsername }
            val avatarUrl = user.optString("profile_pic_url_hd").ifBlank {
                user.optString("profile_pic_url").ifBlank { null }
            }
            val channelId = "ig_${cleanUsername.lowercase()}"
            val edges = user.optJSONObject("edge_owner_to_timeline_media")?.optJSONArray("edges")
            val posts = mutableListOf<MediaVideo>()
            if (edges != null) {
                for (i in 0 until edges.length()) {
                    val node = edges.getJSONObject(i).optJSONObject("node") ?: continue
                    val shortcode = node.optString("shortcode")
                    if (shortcode.isBlank()) continue
                    val isVideo = node.optBoolean("is_video", false)
                    val displayUrl = node.optString("display_url", "")
                    val takenAt = node.optLong("taken_at_timestamp", 0L) * 1000L
                    val captionEdges = node.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")
                    val captionText = captionEdges?.optJSONObject(0)?.optJSONObject("node")?.optString("text", "") ?: ""
                    val title = captionText.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
                        ?: "$fullName ${if (isVideo) "Reel" else "Post"}"
                    val igType = if (isVideo) InstagramMediaType.REEL else InstagramMediaType.IMAGE
                    val views = node.optLong(
                        "video_view_count",
                        node.optJSONObject("edge_media_preview_like")?.optLong("count", 0L) ?: 0L
                    )

                    posts.add(
                        MediaVideo(
                            videoId = "ig_$shortcode",
                            title = title,
                            channelId = channelId,
                            channelName = fullName,
                            publishedAtEpochMillis = if (takenAt > 0) takenAt else System.currentTimeMillis(),
                            thumbnailUrl = displayUrl,
                            viewCount = views,
                            isShort = isVideo,
                            isLive = false,
                            durationSeconds = 0L,
                            platform = MediaPlatform.INSTAGRAM,
                            instagramType = igType
                        )
                    )
                }
            }
            InstagramProfile(
                username = cleanUsername,
                fullName = fullName,
                avatarUrl = avatarUrl,
                posts = posts
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchProfile error: ${e.message}", e)
            InstagramProfile(
                username = cleanUsername,
                fullName = cleanUsername,
                avatarUrl = null,
                posts = emptyList()
            )
        } finally {
            connection?.disconnect()
        }
    }
}
