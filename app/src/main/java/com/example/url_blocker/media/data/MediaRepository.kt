package com.example.url_blocker.media.data

import android.content.Context
import android.util.Log
import com.example.url_blocker.media.model.MediaChannelUpdate
import com.example.url_blocker.media.model.MediaVideo
import com.example.url_blocker.media.model.SavedChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Repository for the Media tab: manages saved channels (add/remove/select,
 * persisted in SharedPreferences) and the latest-videos feed (YouTube RSS,
 * cached to a file per channel so the tab loads instantly offline).
 *
 * All IO happens on [Dispatchers.IO]; the channel list reads are SharedPreferences
 * reads (safe on any thread).
 */
class MediaRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Saved channels ──────────────────────────────────────────────

    /**
     * Saved channels, oldest first. A new install is seeded with the default
     * channel (Safina Society) so the Media tab isn't empty — but ONLY on the
     * very first read (KEY_CHANNELS never written). If the user later removes
     * every channel, the empty list is respected: the default must NOT come
     * back.
     */
    fun getSavedChannels(): List<SavedChannel> {
        val json = prefs.getString(KEY_CHANNELS, null)
        if (json == null) {
            saveChannels(listOf(DEFAULT_CHANNEL))
            return listOf(DEFAULT_CHANNEL)
        }
        return parseChannels(json)
    }

    /** Result of adding a channel: success (with the channel) or an error message. */
    sealed class AddChannelResult {
        data class Success(val channel: SavedChannel) : AddChannelResult()
        data class Error(val message: String) : AddChannelResult()
    }

    /**
     * Resolves [input] (bare id, channel URL or @handle) and saves the channel.
     * Network resolution of handles runs on a background dispatcher. The
     * avatar is NOT fetched here (it would block the add dialog on a page
     * fetch) — [fillMissingAvatars] picks it up right after, triggered by the
     * Media tab's channel-change effect; until then the UI shows initials.
     */
    suspend fun addChannel(input: String): AddChannelResult = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return@withContext AddChannelResult.Error("Enter a channel id, URL or @handle")
        }
        val channelId = ChannelIdResolver.resolve(trimmed)
        if (channelId == null) {
            return@withContext AddChannelResult.Error(
                "Couldn't find that channel. Check the handle or paste the channel URL."
            )
        }
        val existing = getSavedChannels()
        if (existing.any { it.channelId == channelId }) {
            return@withContext AddChannelResult.Error("That channel is already saved")
        }
        val channel = SavedChannel(
            channelId = channelId,
            displayName = trimmed.removePrefix("@"),
            sourceRef = trimmed
        )
        saveChannels(existing + channel)
        AddChannelResult.Success(channel)
    }

    fun removeChannel(channelId: String) {
        val channels = getSavedChannels().filterNot { it.channelId == channelId }
        saveChannels(channels)
        if (getSelectedChannelId() == channelId) {
            setSelectedChannel(channels.firstOrNull()?.channelId)
        }
    }

    fun getSelectedChannelId(): String? =
        prefs.getString(KEY_SELECTED_CHANNEL, null)

    fun setSelectedChannel(channelId: String?) {
        prefs.edit().putString(KEY_SELECTED_CHANNEL, channelId).apply()
    }

    private fun saveChannels(channels: List<SavedChannel>) {
        val arr = JSONArray()
        channels.forEach { c ->
            arr.put(
                JSONObject()
                    .put("channelId", c.channelId)
                    .put("displayName", c.displayName)
                    .put("sourceRef", c.sourceRef)
                    .put("avatarUrl", c.avatarUrl ?: "")
            )
        }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    private fun parseChannels(json: String): List<SavedChannel> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                SavedChannel(
                    channelId = o.getString("channelId"),
                    displayName = o.optString("displayName", o.getString("channelId")),
                    sourceRef = o.optString("sourceRef", o.getString("channelId")),
                    avatarUrl = o.optString("avatarUrl", "").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches missing channel avatars (best-effort) and persists the updated
     * channel list. Returns the updated list when anything changed, else null.
     */
    suspend fun fillMissingAvatars(channels: List<SavedChannel>): List<SavedChannel>? {
        if (channels.none { it.avatarUrl == null }) return null
        var changed = false
        val updated = channels.map { c ->
            if (c.avatarUrl == null) {
                val url = ChannelAvatarResolver.fetchAvatar(c.channelId)
                if (url != null) {
                    changed = true
                    c.copy(avatarUrl = url)
                } else {
                    c
                }
            } else {
                c
            }
        }
        if (!changed) return null
        saveChannels(updated)
        return updated
    }

    // ── Latest videos (RSS + cache) ─────────────────────────────────

    /**
     * Reads the cached videos for [channelId] (never network). Returns null
     * when nothing is cached yet. Also surfaces the cache age so the UI can
     * show a "cached" hint.
     */
    fun getCachedVideos(channelId: String): Pair<List<MediaVideo>, Long>? {
        val file = cacheFile(channelId)
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val savedAt = obj.optLong("savedAt", 0L)
            val videos = parseVideos(obj.optJSONArray("videos"))
            if (videos.isEmpty()) null else videos to savedAt
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetches the channel's RSS feed, parses the latest videos, updates the
     * local cache and returns them. Returns null on any network/parse failure
     * so the UI can fall back to the cache and show an error hint.
     */
    suspend fun refreshVideos(channelId: String): List<MediaVideo>? = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/atom+xml")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // Classify Shorts from the channel's /shorts tab (many Shorts omit
            // the #shorts hashtag, so title-only detection misses most of them).
            // Best-effort: an empty set degrades to hashtag detection.
            val shortsIds = ShortsIdResolver.fetchShortsIds(channelId)
            val videos = YouTubeRssParser.parse(body, shortsIds)
            // Trace every feed item straight from the parsed RSS so we can
            // confirm the videoId handed to the embedded player belongs to
            // this exact RSS entry (no stale/mixed/hardcoded ids).
            videos.forEach { v ->
                Log.d(
                    TAG,
                    "RSS_VIDEO channelId=$channelId videoId=${v.videoId} " +
                        "videoUrl=https://www.youtube.com/watch?v=${v.videoId} title=${v.title}"
                )
            }
            if (videos.isNotEmpty()) writeCache(channelId, videos)
            videos
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ── Media notifications (channel updates) ───────────────────────

    /**
     * Whether the app posts a notification when a saved channel uploads a new
     * video. Default ON; the toggle lives on the home (Quran) tab.
     */
    fun isMediaNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_MEDIA_NOTIFICATIONS_ENABLED, DEFAULT_MEDIA_NOTIFICATIONS_ENABLED)

    /** Persists the media-notifications toggle state. */
    fun setMediaNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    /** Video ids already notified about (dedup — never re-notify the same video). */
    fun getNotifiedVideoIds(): Set<String> =
        prefs.getStringSet(KEY_NOTIFIED_VIDEOS, emptySet()) ?: emptySet()

    /** Persists the notified-video id set (capped so it can't grow unbounded). */
    fun markVideosNotified(ids: Set<String>) {
        val capped = ids.toList().takeLast(MAX_NOTIFIED_VIDEOS).toSet()
        prefs.edit().putStringSet(KEY_NOTIFIED_VIDEOS, capped).apply()
    }

    /**
     * Builds the home-page "Latest Updates" feed: the newest video per saved
     * channel (from cached feeds — never network), newest first. Empty when
     * nothing is cached yet.
     */
    fun buildChannelUpdates(videos: List<MediaVideo>): List<MediaChannelUpdate> =
        videos
            .groupBy { it.channelId }
            .map { (channelId, vs) ->
                val newest = vs.maxByOrNull { it.publishedAtEpochMillis }!!
                MediaChannelUpdate(
                    channelId = channelId,
                    channelName = newest.channelName.ifBlank { channelId },
                    latestVideoId = newest.videoId,
                    latestVideoTitle = newest.title,
                    publishedAtEpochMillis = newest.publishedAtEpochMillis
                )
            }
            .sortedByDescending { it.publishedAtEpochMillis }

    // ── Aggregate feed (Subscriptions-style: every saved channel) ──

    /**
     * Merges the cached videos of ALL [channels] (never network), newest
     * first. Used for the instant first paint of the Media tab.
     */
    fun getAllCachedVideos(channels: List<SavedChannel>): List<MediaVideo> =
        channels
            .mapNotNull { getCachedVideos(it.channelId)?.first }
            .flatten()
            .sortedByDescending { it.publishedAtEpochMillis }

    /**
     * Refreshes EVERY channel's RSS feed and merges the results, newest
     * first. Returns null only when every channel failed (the UI falls back to
     * the cache and shows an error hint); partial failures keep the feeds that
     * succeeded.
     */
    suspend fun refreshAllVideos(channels: List<SavedChannel>): List<MediaVideo>? {
        var anySucceeded = false
        val merged = ArrayList<MediaVideo>()
        for (channel in channels) {
            val fresh = refreshVideos(channel.channelId)
            if (fresh != null) {
                anySucceeded = true
                merged.addAll(fresh)
            }
        }
        if (!anySucceeded) return null
        return merged.sortedByDescending { it.publishedAtEpochMillis }
    }

    private fun cacheFile(channelId: String): File =
        File(appContext.filesDir, "media_videos_$channelId.json")

    private fun writeCache(channelId: String, videos: List<MediaVideo>) {
        val arr = JSONArray()
        videos.forEach { v ->
            arr.put(
                JSONObject()
                    .put("videoId", v.videoId)
                    .put("title", v.title)
                    .put("channelId", v.channelId)
                    .put("channelName", v.channelName)
                    .put("publishedAt", v.publishedAtEpochMillis)
                    .put("thumbnailUrl", v.thumbnailUrl)
                    .put("viewCount", v.viewCount)
                    .put("isShort", v.isShort)
            )
        }
        val obj = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("videos", arr)
        cacheFile(channelId).writeText(obj.toString(), Charsets.UTF_8)
    }

    private fun parseVideos(arr: JSONArray?): List<MediaVideo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                MediaVideo(
                    videoId = o.getString("videoId"),
                    title = o.optString("title", ""),
                    channelId = o.optString("channelId", ""),
                    channelName = o.optString("channelName", ""),
                    publishedAtEpochMillis = o.optLong("publishedAt", 0L),
                    thumbnailUrl = o.optString("thumbnailUrl", ""),
                    viewCount = o.optLong("viewCount", 0L),
                    isShort = o.optBoolean("isShort", false)
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private companion object {
        const val TAG = "MediaRepository"
        const val PREFS_NAME = "media_prefs"
        const val KEY_CHANNELS = "saved_channels"
        const val KEY_SELECTED_CHANNEL = "selected_channel"
        const val KEY_MEDIA_NOTIFICATIONS_ENABLED = "media_notifications_enabled"
        const val KEY_NOTIFIED_VIDEOS = "notified_video_ids"
        const val MAX_NOTIFIED_VIDEOS = 200
        const val DEFAULT_MEDIA_NOTIFICATIONS_ENABLED = true

        val DEFAULT_CHANNEL = SavedChannel(
            channelId = "UC2cX3SmsdWsrRS8t_5zvzEw", // Safina Society (@SafinaSociety)
            displayName = "Safina Society",
            sourceRef = "@SafinaSociety"
        )
    }
}
