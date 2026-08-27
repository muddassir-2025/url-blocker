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

sealed class InstagramFeedResult {
    data class Success(
        val username: String,
        val fullName: String,
        val avatarUrl: String?,
        val items: List<MediaVideo>
    ) : InstagramFeedResult()

    data class Error(val message: String) : InstagramFeedResult()
}

/**
 * Pluggable provider interface for Instagram content extraction.
 * Guarantees zero Instagram login, zero cookies, and normalized MediaVideo items.
 */
interface InstagramSource {
    val name: String
    suspend fun fetchProfile(username: String): InstagramFeedResult
}

/**
 * Mobile Web Profile API provider. Retrieves public Instagram user metadata,
 * direct CDN image URLs, and direct .mp4 video stream URLs without authentication.
 */
class WebProfileInstagramSource : InstagramSource {
    override val name: String = "WebProfileApi"

    companion object {
        private const val TAG = "WebProfileSource"
        private const val IPHONE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
    }

    override suspend fun fetchProfile(username: String): InstagramFeedResult = withContext(Dispatchers.IO) {
        val cleanUsername = username.removePrefix("@").trim()
        val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$cleanUsername"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", IPHONE_USER_AGENT)
                setRequestProperty("X-IG-App-ID", "936619743392459")
                setRequestProperty("X-ASBD-ID", "129477")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Referer", "https://www.instagram.com/$cleanUsername/")
                setRequestProperty("Sec-Fetch-Mode", "cors")
                setRequestProperty("Sec-Fetch-Site", "same-origin")
                setRequestProperty("Accept", "*/*")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(body)
                val user = root.optJSONObject("data")?.optJSONObject("user")
                if (user != null) {
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
                            val videoUrl = node.optString("video_url", "").ifBlank { null }
                            val takenAt = node.optLong("taken_at_timestamp", 0L) * 1000L
                            val captionEdges = node.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")
                            val captionText = captionEdges?.optJSONObject(0)?.optJSONObject("node")?.optString("text", "") ?: ""
                            val title = captionText.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
                                ?: "$fullName ${if (isVideo) "Reel" else "Photo"}"
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
                                    publishedAtEpochMillis = if (takenAt > 0) takenAt else (System.currentTimeMillis() - (i * 3600_000L)),
                                    thumbnailUrl = displayUrl,
                                    viewCount = views,
                                    isShort = isVideo,
                                    isLive = false,
                                    durationSeconds = 0L,
                                    platform = MediaPlatform.INSTAGRAM,
                                    instagramType = igType,
                                    mediaUrl = videoUrl ?: displayUrl
                                )
                            )
                        }
                    }

                    if (posts.isNotEmpty()) {
                        return@withContext InstagramFeedResult.Success(
                            username = cleanUsername,
                            fullName = fullName,
                            avatarUrl = avatarUrl,
                            items = posts
                        )
                    }
                }
            } else {
                Log.w(TAG, "fetchProfile status: $responseCode for $cleanUsername")
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchProfile failed: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
        InstagramFeedResult.Error("Could not fetch profile via $name")
    }
}
