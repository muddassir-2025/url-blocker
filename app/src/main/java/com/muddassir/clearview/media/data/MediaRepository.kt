package com.muddassir.clearview.media.data

import android.content.Context
import android.util.Log
import com.muddassir.clearview.media.model.FeedFilter
import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.media.model.MediaChannelUpdate
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.SavedChannel
import com.muddassir.clearview.media.model.SavedPlaylist
import com.muddassir.clearview.media.util.MediaUpdates
import com.muddassir.clearview.media.util.PlaylistPageParser
import com.muddassir.clearview.media.util.decodeFeedFilter
import com.muddassir.clearview.media.util.encodeFeedFilter
import com.muddassir.clearview.media.util.extractYouTubePlaylistId
import com.muddassir.clearview.media.util.extractYouTubeVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    init {
        // Preload the duration resolver's on-disk cache so resolved durations
        // survive restarts (no watch-page re-fetch on the first refresh).
        VideoDurationResolver.init(appContext)
    }

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

    /** Result of adding a channel: success (with the channel/channels) or an error message. */
    sealed class AddChannelResult {
        data class Success(val channels: List<SavedChannel>) : AddChannelResult() {
            constructor(channel: SavedChannel) : this(listOf(channel))
            val channel: SavedChannel get() = channels.first()
        }
        data class Error(val message: String) : AddChannelResult()
    }

    /**
     * Resolves [input] (bare id, channel URL or @handle) and saves the channel.
     * Supports both YouTube channels and Instagram public profiles — if both
     * are found for the same handle/query, both are added.
     */
    suspend fun addChannel(input: String): AddChannelResult = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return@withContext AddChannelResult.Error("Enter a channel id, URL or @handle")
        }

        val existing = getSavedChannels()
        val toAdd = mutableListOf<SavedChannel>()

        // 1. Try resolving YouTube channel
        val isExplicitInstagram = trimmed.contains("instagram.com/") || trimmed.contains("instagr.am/")
        if (!isExplicitInstagram) {
            val ytChannelId = ChannelIdResolver.resolve(trimmed)
            if (ytChannelId != null && existing.none { it.channelId == ytChannelId } && toAdd.none { it.channelId == ytChannelId }) {
                toAdd.add(
                    SavedChannel(
                        channelId = ytChannelId,
                        displayName = channelDisplayName(trimmed),
                        sourceRef = trimmed,
                        addedAtEpochMillis = System.currentTimeMillis(),
                        platform = MediaPlatform.YOUTUBE
                    )
                )
            }
        }

        // 2. Try resolving Instagram profile
        val igUsername = InstagramResolver.extractUsername(trimmed)
        if (igUsername != null) {
            val igProfile = InstagramResolver.resolve(trimmed)
            if (igProfile != null) {
                val igChannelId = "ig_${igProfile.username.lowercase()}"
                if (existing.none { it.channelId == igChannelId } && toAdd.none { it.channelId == igChannelId }) {
                    toAdd.add(
                        SavedChannel(
                            channelId = igChannelId,
                            displayName = if (igProfile.fullName.isNotBlank()) igProfile.fullName else "@${igProfile.username}",
                            sourceRef = "@${igProfile.username}",
                            avatarUrl = igProfile.avatarUrl?.takeIf { InstagramRssParser.isRealAvatarUrl(it) },
                            addedAtEpochMillis = System.currentTimeMillis(),
                            platform = MediaPlatform.INSTAGRAM
                        )
                    )
                    if (igProfile.posts.isNotEmpty()) {
                        writeCache(igChannelId, igProfile.posts)
                    }
                }
            }
        }

        if (toAdd.isEmpty()) {
            val already = existing.any {
                it.sourceRef.equals(trimmed, ignoreCase = true) ||
                    it.channelId == ChannelIdResolver.extractChannelId(trimmed) ||
                    (igUsername != null && it.channelId == "ig_${igUsername.lowercase()}")
            }
            return@withContext if (already) {
                AddChannelResult.Error("That channel / profile is already saved")
            } else {
                AddChannelResult.Error("Couldn't find that channel or Instagram profile. Check the handle or paste the URL.")
            }
        }

        saveChannels(existing + toAdd)

        // Baseline notification guard
        for (channel in toAdd) {
            if (channel.platform == MediaPlatform.YOUTUBE) {
                runCatching {
                    refreshVideos(channel.channelId, enrich = false)?.let { fresh ->
                        if (fresh.isNotEmpty()) {
                            markVideosNotified(getNotifiedVideoIds() + fresh.map { it.videoId })
                        }
                    }
                }
            }
        }

        AddChannelResult.Success(toAdd)
    }

    /**
     * Human-friendly channel name from what the user pasted: the @handle for
     * handle / handle-URL inputs (incl. Unicode handles like @الفلاح-هدف), the
     * input itself otherwise (bare ids, /channel/ URLs). Never the full pasted
     * URL for an /@ URL.
     */
    private fun channelDisplayName(input: String): String = when {
        input.contains("/@") ->
            input.substringAfterLast("/@").substringBefore('/').substringBefore('?').trim()
        input.startsWith("@") -> input.removePrefix("@")
        else -> input
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
                    .put("addedAt", c.addedAtEpochMillis)
                    .put("platform", c.platform.name)
                    .put("instagramType", c.instagramType?.name ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    private fun parseChannels(json: String): List<SavedChannel> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val platform = runCatching {
                    MediaPlatform.valueOf(o.optString("platform", "YOUTUBE"))
                }.getOrDefault(MediaPlatform.YOUTUBE)
                val rawAvatar = o.optString("avatarUrl", "").ifBlank { null }
                val cleanAvatar = if (platform == MediaPlatform.INSTAGRAM && !InstagramRssParser.isRealAvatarUrl(rawAvatar)) null else rawAvatar

                SavedChannel(
                    channelId = o.getString("channelId"),
                    displayName = o.optString("displayName", o.getString("channelId")),
                    sourceRef = o.optString("sourceRef", o.getString("channelId")),
                    avatarUrl = cleanAvatar,
                    // Missing on channels saved by older builds → 0 → the
                    // worker's subscription guard stays inactive for them.
                    addedAtEpochMillis = o.optLong("addedAt", 0L),
                    platform = platform,
                    instagramType = o.optString("instagramType", "").takeIf { it.isNotBlank() }?.let {
                        runCatching { InstagramMediaType.valueOf(it) }.getOrNull()
                    }
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
                val url = if (c.platform == MediaPlatform.INSTAGRAM) {
                    val user = c.channelId.removePrefix("ig_")
                    InstagramResolver.fetchProfile(user)?.avatarUrl?.takeIf { InstagramRssParser.isRealAvatarUrl(it) }
                } else {
                    ChannelAvatarResolver.fetchAvatar(c.channelId)
                }
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

    // ── Manual videos (added by URL) ───────────────────────────────

    /** Result of resolving a pasted video URL into a [MediaVideo]. */
    sealed class ResolveVideoResult {
        /** Resolved metadata; the caller should save it as a manual video. */
        data class Success(val video: MediaVideo) : ResolveVideoResult()

        /** The video is already part of [channel]'s feed (no need to add). */
        data class AlreadyExists(val video: MediaVideo) : ResolveVideoResult()

        data class Error(val message: String) : ResolveVideoResult()
    }

    /**
     * Resolves a user-pasted YouTube URL for [fallbackChannel] (the channel
     * whose feed the user is adding into). Verifies the video against the
     * channel's cached/fresh RSS first (best-effort); when it isn't there,
     * fetches metadata via YouTube's public oEmbed endpoint (title, channel
     * name, thumbnail — no duration, no description). Publication time stays
     * 0 (unknown) so the video is never presented as newly published.
     */
    suspend fun resolveVideoByUrl(
        input: String,
        fallbackChannel: SavedChannel?
    ): ResolveVideoResult = withContext(Dispatchers.IO) {
        val videoId = extractYouTubeVideoId(input)
            ?: return@withContext ResolveVideoResult.Error(
                "That doesn't look like a YouTube video URL."
            )
        // Already in this channel's cache? Reuse the RSS metadata.
        fallbackChannel?.let { channel ->
            getCachedVideos(channel.channelId)
                ?.first?.firstOrNull { it.videoId == videoId }
                ?.let { return@withContext ResolveVideoResult.AlreadyExists(it) }
        }
        // Already in the channel's LIVE feed (cache stale)? Same.
        fallbackChannel?.let { channel ->
            fetchFeedVideos(channel.channelId)
                ?.firstOrNull { it.videoId == videoId }
                ?.let { return@withContext ResolveVideoResult.AlreadyExists(it) }
        }
        // Not in the feed — fetch metadata from oEmbed (no API key needed).
        val meta = fetchOEmbedMetadata(videoId)
            ?: return@withContext ResolveVideoResult.Error(
                "Couldn't load video info. Check the URL and your connection."
            )
        ResolveVideoResult.Success(
            MediaVideo(
                videoId = videoId,
                title = meta.title,
                channelId = fallbackChannel?.channelId ?: "",
                channelName = fallbackChannel?.displayName ?: meta.authorName,
                publishedAtEpochMillis = 0L, // unknown date — never pretend it's new
                thumbnailUrl = meta.thumbnailUrl,
                viewCount = 0L,
                isShort = false,
                // Same live-thumbnail signal as the RSS parser.
                isLive = meta.thumbnailUrl.contains("_live.", ignoreCase = true),
                // oEmbed omits duration, so resolve it from the watch page too
                // (best-effort; the card just shows no time badge on failure).
                durationSeconds = runCatching {
                    VideoDurationResolver.fetchDuration(videoId)
                }.getOrNull() ?: 0L
            )
        )
    }

    private data class OEmbedMeta(
        val title: String,
        val authorName: String,
        val thumbnailUrl: String
    )

    /** Fetches title / author / thumbnail via YouTube's public oEmbed endpoint. */
    private fun fetchOEmbedMetadata(videoId: String): OEmbedMeta? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val apiUrl = "https://www.youtube.com/oembed?url=" +
            URLEncoder.encode(watchUrl, "UTF-8") + "&format=json"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(body)
            OEmbedMeta(
                title = o.optString("title", ""),
                authorName = o.optString("author_name", ""),
                thumbnailUrl = o.optString("thumbnail_url", "")
            )
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ── Latest videos (RSS + cache) ─────────────────────────────────

    /**
     * Reads the cached videos for [channelId] (never network). Returns null
     * when nothing is cached yet. Also surfaces the cache age so the UI can
     * show a "cached" hint.
     */
    fun getCachedVideos(channelId: String): Pair<List<MediaVideo>, Long>? =
        readVideosFile(cacheFile(channelId))

    /**
     * Fetches the channel's RSS feed, parses the latest videos, updates the
     * local cache and returns them. Returns null on any network/parse failure
     * so the UI can fall back to the cache and show an error hint.
     *
     * [enrich] skips the expensive watch-page duration enrichment (used by the
     * add-channel path so the dialog stays fast — the feed's own refresh
     * enriches on its next run).
     */
    suspend fun refreshVideos(
        channelId: String,
        enrich: Boolean = true
    ): List<MediaVideo>? = withContext(Dispatchers.IO) {
        if (channelId.startsWith("ig_")) {
            val username = channelId.removePrefix("ig_")
            val cachedRaw = getCachedVideos(channelId)?.first ?: emptyList()
            val cached = cachedRaw.filterNot { it.videoId.endsWith("_reels") || it.videoId.endsWith("_posts") }
            val profile = try { InstagramResolver.fetchProfile(username) } catch (e: Exception) { null }
            if (profile != null && profile.posts.isNotEmpty()) {
                val cleanPosts = profile.posts.filterNot { it.videoId.endsWith("_reels") || it.videoId.endsWith("_posts") }
                // Merge: fresh items take priority, then cached items not in fresh set
                val freshIds = cleanPosts.map { it.videoId }.toSet()
                val merged = cleanPosts + cached.filter { it.videoId !in freshIds }
                val sorted = merged.sortedByDescending { it.publishedAtEpochMillis }
                writeCache(channelId, sorted)
                return@withContext sorted
            }
            // Fetch failed — return cached content (never empty on failure)
            return@withContext cached.ifEmpty { null }
        }

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
            val fresh = YouTubeRssParser.parse(body, shortsIds)
            // Carry over already-resolved durations from the cache so a refresh
            // never re-fetches watch pages for videos we've enriched before
            // (the RSS feed itself carries no duration field).
            val knownDurations = getCachedVideos(channelId)?.first
                ?.associate { it.videoId to it.durationSeconds }
                ?.filterValues { it > 0L } ?: emptyMap()
            val withKnown = fresh.map { v ->
                val known = knownDurations[v.videoId]
                if (known != null) v.copy(durationSeconds = known) else v
            }
            // Then enrich the newest still-unknown videos with their duration
            // (best-effort: a failure just leaves the badge off). The add-channel
            // path skips enrichment so the dialog stays fast — the feed effect
            // enriches on its own refresh right after.
            val videos = if (enrich) enrichDurations(withKnown) else withKnown
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
            // A manually added video is never "new" — even when it later shows
            // up in RSS, it must not generate a notification (the user added it
            // deliberately as old content). Baseline it alongside the RSS items.
            val manualIds = MediaLibraryStore(appContext)
                .getManuallyAddedVideos().map { it.videoId }.toSet()
            val baseline = videos.filter { it.videoId in manualIds }.map { it.videoId }
            if (baseline.isNotEmpty()) {
                markVideosNotified(getNotifiedVideoIds() + baseline)
            }
            if (videos.isNotEmpty()) writeCache(channelId, videos)
            videos
        } catch (e: CancellationException) {
            // Never report a cancelled refresh as a channel failure — the caller
            // (the feed effect) restarts and refetches anyway. Rethrowing keeps
            // the cancellation propagating so a dropped channel can't poison the
            // merged feed with a bogus null.
            throw e
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Best-effort enrichment: fills [MediaVideo.durationSeconds] for the newest
     * [top] videos that don't have one yet. Fetching a watch page per video is
     * expensive, so it's capped to the head of the feed — the values persist in
     * the cache and are carried over on later refreshes. Never fails a refresh:
     * any fetch error leaves that video's badge off.
     *
     * Fetches run with BOUNDED parallelism ([concurrency] watch-page requests
     * in flight at once): a multi-channel refresh would otherwise stall on up
     * to 10 sequential ~1–3s requests per channel. The batches are awaited in
     * order so the result list keeps its original (newest-first) order.
     */
    private suspend fun enrichDurations(
        videos: List<MediaVideo>,
        top: Int = 10,
        concurrency: Int = 4
    ): List<MediaVideo> {
        val missing = videos.filter { it.durationSeconds <= 0L }.take(top)
        if (missing.isEmpty()) return videos
        val byId = videos.associateBy { it.videoId }.toMutableMap()
        missing.chunked(concurrency).forEach { batch ->
            val results = coroutineScope {
                batch.map { v ->
                    async {
                        v.videoId to runCatching {
                            VideoDurationResolver.fetchDuration(v.videoId)
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            for ((videoId, seconds) in results) {
                if (seconds != null && seconds > 0L) {
                    byId[videoId]?.let { original ->
                        byId[videoId] = original.copy(durationSeconds = seconds)
                    }
                }
            }
        }
        return videos.map { byId[it.videoId] ?: it }
    }

    /**
     * Fetches a channel's RSS and parses the latest videos WITHOUT the Shorts
     * classification and WITHOUT touching the cache — the lightweight check
     * used when resolving a user-pasted video URL (verifies membership in the
     * channel's real feed). Null on any network/parse failure.
     */
    private fun fetchFeedVideos(channelId: String): List<MediaVideo>? {
        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/atom+xml")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            YouTubeRssParser.parse(body, emptySet())
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

    // ── Latest Updates feed (persisted history) ─────────────────────

    /**
     * The home tab's "Latest Updates" feed: the newest [MediaUpdates.MAX]
     * channel updates recorded by the background worker. Each entry matches a
     * notification the user received; dismissing one removes it permanently.
     */
    fun getUpdatesHistory(): List<MediaChannelUpdate> =
        MediaUpdates.decode(prefs.getString(KEY_UPDATES_HISTORY, null)).take(MediaUpdates.MAX)

    /**
     * Records freshly detected updates (called by the worker right after
     * notifying), merged into the existing history — deduped by video id,
     * newest first, capped at [MediaUpdates.MAX].
     */
    fun recordChannelUpdates(updates: List<MediaChannelUpdate>) {
        if (updates.isEmpty()) return
        val merged = MediaUpdates.merge(getUpdatesHistory(), updates)
        prefs.edit().putString(KEY_UPDATES_HISTORY, MediaUpdates.encode(merged)).apply()
    }

    /** Removes one update from the feed permanently (it won't re-appear). */
    fun dismissUpdate(latestVideoId: String) {
        val remaining = getUpdatesHistory().filterNot { it.latestVideoId == latestVideoId }
        prefs.edit().putString(KEY_UPDATES_HISTORY, MediaUpdates.encode(remaining)).apply()
    }

    /**
     * How many of [updates] the user hasn't seen yet (drives the Media-tab
     * badge). An update is "unread" until its id is in the seen set.
     */
    fun countUnreadUpdates(updates: List<MediaChannelUpdate>): Int =
        updates.count { it.latestVideoId !in getSeenUpdateIds() }

    /** Marks the given update ids as seen; returns how many were newly marked. */
    fun markUpdatesSeen(videoIds: List<String>): Int {
        if (videoIds.isEmpty()) return 0
        val seen = getSeenUpdateIds()
        val newly = videoIds.filter { it !in seen }.toSet()
        if (newly.isEmpty()) return 0
        val merged = (seen + newly).toList().takeLast(MAX_NOTIFIED_VIDEOS).toSet()
        prefs.edit().putStringSet(KEY_SEEN_UPDATE_IDS, merged).apply()
        return newly.size
    }

    private fun getSeenUpdateIds(): Set<String> =
        prefs.getStringSet(KEY_SEEN_UPDATE_IDS, emptySet()) ?: emptySet()

    // ── Feed filters (persisted across restarts, per context) ──────

    /**
     * The saved feed filter for [channelId], or the All Feed filter when
     * [channelId] is null. Every channel keeps its OWN filter, separate from
     * the All Feed one. Defaults when never set or corrupt.
     */
    fun getFeedFilter(channelId: String? = null): FeedFilter =
        decodeFeedFilter(prefs.getString(feedFilterKey(channelId), null)) ?: FeedFilter()

    /** Persists the feed filter for [channelId] (null = All Feed). */
    fun setFeedFilter(filter: FeedFilter, channelId: String? = null) {
        prefs.edit().putString(feedFilterKey(channelId), encodeFeedFilter(filter)).apply()
    }

    /** Per-context storage key: the All Feed key, or a per-channel key. */
    private fun feedFilterKey(channelId: String?): String =
        if (channelId == null) KEY_FEED_FILTER
        else KEY_FEED_FILTER + "_channel_" + channelId

    /**
     * One-time seed for a fresh install (or the first run after this feature
     * arrived): pre-fills the history from the cached feeds so the feed isn't
     * empty before the first background check. Only runs when the history key
     * has NEVER been written, so updates the user later dismisses can never be
     * resurrected by another seed.
     */
    fun ensureUpdatesHistorySeeded(channels: List<SavedChannel>) {
        if (prefs.contains(KEY_UPDATES_HISTORY)) return
        val seeded = buildChannelUpdates(
            channels.mapNotNull { getCachedVideos(it.channelId)?.first }.flatten()
        )
        if (seeded.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_UPDATES_HISTORY, MediaUpdates.encode(seeded.take(MediaUpdates.MAX)))
                .apply()
        }
    }

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
     * first. Channels are refreshed CONCURRENTLY with [concurrency] feeds in
     * flight at once (each channel's own duration enrichment is already
     * internally bounded), so a multi-channel feed loads much faster than a
     * strictly sequential pass. A [batchDelayMs] pause is inserted between
     * batches so the request bursts don't stack into one rate-limit window.
     * A channel whose refresh FAILED keeps its cached videos (if any), so a
     * transient failure never drops the channel from the merged feed — this is
     * what lets the background worker still see its content and what keeps the
     * Media tab's own merge effective. Returns null only when every channel
     * failed AND nothing is cached (callers then fall back to the cache / show
     * an error hint). A legitimately EMPTY feed (fetched fine, no entries) is
     * not a failure and contributes nothing.
     */
    suspend fun refreshAllVideos(
        channels: List<SavedChannel>,
        concurrency: Int = 3,
        batchDelayMs: Long = 500
    ): List<MediaVideo>? {
        var anySucceeded = false
        val merged = ArrayList<MediaVideo>()
        channels.chunked(concurrency).forEachIndexed { index, batch ->
            // Brief pause between batches (never before the first): each batch
            // can fire up to `concurrency` channels × (RSS + /shorts + watch
            // pages) at once, so spacing them keeps the peak request rate down.
            if (index > 0 && batchDelayMs > 0L) delay(batchDelayMs)
            // refreshVideos never throws except on cancellation (which must
            // propagate — a cancelled channel is NOT a failed channel), so the
            // batch awaits raw and lets awaitAll rethrow on cancel. A failed
            // refresh falls back to the channel's cached feed (read on IO, so
            // the file read never touches the caller's main thread) — the
            // channel isn't dropped from the merged result.
            val results = coroutineScope {
                batch.map { channel ->
                    async {
                        val channelId = channel.channelId
                        withContext(Dispatchers.IO) {
                            refreshVideos(channelId) ?: getCachedVideos(channelId)?.first
                        }
                    }
                }.awaitAll()
            }
            for (fresh in results) {
                if (fresh != null) {
                    anySucceeded = true
                    merged.addAll(fresh)
                }
            }
        }
        if (!anySucceeded) return null
        return merged.sortedByDescending { it.publishedAtEpochMillis }
    }

    private fun cacheFile(channelId: String): File =
        File(appContext.filesDir, "media_videos_$channelId.json")

    private fun writeCache(channelId: String, videos: List<MediaVideo>) {
        writeVideosFile(cacheFile(channelId), videos)
    }

    /** Shared cache reader for channel and playlist caches (same JSON layout). */
    private fun readVideosFile(file: File): Pair<List<MediaVideo>, Long>? {
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val savedAt = obj.optLong("savedAt", 0L)
            val videos = parseVideos(obj.optJSONArray("videos"))
                .filterNot { it.videoId.endsWith("_reels") || it.videoId.endsWith("_posts") }
            if (videos.isEmpty()) null else videos to savedAt
        } catch (e: Exception) {
            null
        }
    }

    /** Shared cache writer for channel and playlist caches (same JSON layout). */
    private fun writeVideosFile(file: File, videos: List<MediaVideo>) {
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
                    .put("isLive", v.isLive)
                    .put("durationSeconds", v.durationSeconds)
                    .put("isOfflineAudio", v.isOfflineAudio)
                    .put("platform", v.platform.name)
                    .put("instagramType", v.instagramType?.name ?: JSONObject.NULL)
                    .put("mediaUrl", v.mediaUrl ?: JSONObject.NULL)
                    .put("instagramUrl", v.instagramUrl ?: JSONObject.NULL)
            )
        }
        val obj = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("videos", arr)
        file.writeText(obj.toString(), Charsets.UTF_8)
    }

    private fun parseVideos(arr: JSONArray?): List<MediaVideo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                val platformStr = o.optString("platform", "YOUTUBE")
                val platform = runCatching { MediaPlatform.valueOf(platformStr) }.getOrDefault(MediaPlatform.YOUTUBE)
                val igTypeStr = o.optString("instagramType", "").takeIf { it.isNotBlank() }
                val igType = igTypeStr?.let { runCatching { InstagramMediaType.valueOf(it) }.getOrNull() }
                val mediaUrl = o.optString("mediaUrl", "").takeIf { it.isNotBlank() }
                val instagramUrl = o.optString("instagramUrl", "").takeIf { it.isNotBlank() }
                MediaVideo(
                    videoId = o.getString("videoId"),
                    title = o.optString("title", ""),
                    channelId = o.optString("channelId", ""),
                    channelName = o.optString("channelName", ""),
                    publishedAtEpochMillis = o.optLong("publishedAt", 0L),
                    thumbnailUrl = o.optString("thumbnailUrl", ""),
                    viewCount = o.optLong("viewCount", 0L),
                    isShort = o.optBoolean("isShort", false),
                    isLive = o.optBoolean("isLive", false),
                    durationSeconds = o.optLong("durationSeconds", 0L),
                    isOfflineAudio = o.optBoolean("isOfflineAudio", false),
                    platform = platform,
                    instagramType = igType,
                    mediaUrl = mediaUrl,
                    instagramUrl = instagramUrl
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    // ── Saved playlists (imported by URL) ──────────────────────────

    /**
     * Saved YouTube playlists, oldest first. Unlike channels, a fresh install
     * starts with NO playlists — they're purely user-imported.
     */
    fun getSavedPlaylists(): List<SavedPlaylist> {
        val json = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return parsePlaylists(json)
    }

    /** Result of adding a playlist: success (with the playlist) or an error message. */
    sealed class AddPlaylistResult {
        data class Success(val playlist: SavedPlaylist) : AddPlaylistResult()
        data class Error(val message: String) : AddPlaylistResult()
    }

    /**
     * Resolves [input] (playlist URL or bare id), fetches the playlist's
     * videos, caches them and saves the playlist. Returns a friendly error for
     * non-playlist input, unreachable / invalid playlists, private playlists
     * and network failures.
     */
    suspend fun addPlaylist(input: String): AddPlaylistResult = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return@withContext AddPlaylistResult.Error("Enter a YouTube playlist URL or id")
        }
        val playlistId = extractYouTubePlaylistId(trimmed)
            ?: return@withContext AddPlaylistResult.Error(
                "That doesn't look like a YouTube playlist URL."
            )
        if (getSavedPlaylists().any { it.playlistId == playlistId }) {
            return@withContext AddPlaylistResult.Error("That playlist is already saved")
        }
        val info = fetchPlaylistInfo(playlistId)
            ?: return@withContext AddPlaylistResult.Error(
                "Couldn't load that playlist. Check the URL and your connection."
            )
        if (info.videos.isEmpty()) {
            return@withContext AddPlaylistResult.Error(
                "This playlist is private or unavailable."
            )
        }
        val playlist = SavedPlaylist(
            playlistId = playlistId,
            title = info.title.ifBlank { "YouTube Playlist" },
            sourceRef = trimmed
        )
        savePlaylists(getSavedPlaylists() + playlist)
        writeVideosFile(playlistCacheFile(playlistId), info.videos)
        AddPlaylistResult.Success(playlist)
    }

    fun removePlaylist(playlistId: String) {
        savePlaylists(getSavedPlaylists().filterNot { it.playlistId == playlistId })
        playlistCacheFile(playlistId).delete()
    }

    private fun savePlaylists(playlists: List<SavedPlaylist>) {
        val arr = JSONArray()
        playlists.forEach { p ->
            arr.put(
                JSONObject()
                    .put("playlistId", p.playlistId)
                    .put("title", p.title)
                    .put("sourceRef", p.sourceRef)
            )
        }
        prefs.edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }

    private fun parsePlaylists(json: String): List<SavedPlaylist> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                SavedPlaylist(
                    playlistId = o.getString("playlistId"),
                    title = o.optString("title", "YouTube Playlist"),
                    sourceRef = o.optString("sourceRef", o.getString("playlistId"))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Playlist videos (page scrape + RSS fallback + cache) ──────

    /** Cached videos for [playlistId] (never network). Null when nothing cached. */
    fun getCachedPlaylistVideos(playlistId: String): Pair<List<MediaVideo>, Long>? =
        readVideosFile(playlistCacheFile(playlistId))

    /**
     * Refetches the playlist's videos, updates its cache and returns them in
     * playlist order. Returns null on a network/parse failure (UI falls back
     * to the cache) and an EMPTY list when the playlist is definitively
     * private / has no visible videos (UI shows the friendly message).
     */
    suspend fun refreshPlaylistVideos(playlistId: String): List<MediaVideo>? =
        withContext(Dispatchers.IO) {
            val info = fetchPlaylistInfo(playlistId) ?: return@withContext null
            if (info.videos.isEmpty()) return@withContext emptyList()
            writeVideosFile(playlistCacheFile(playlistId), info.videos)
            info.videos
        }

    private fun playlistCacheFile(playlistId: String): File =
        File(appContext.filesDir, "media_playlist_$playlistId.json")

    /**
     * Fetches the playlist page and parses its `ytInitialData`, then follows
     * EVERY continuation page so the FULL playlist is imported. There are two
     * page formats to handle:
     *
     *  - CLASSIC: the initial HTML embeds the first batch of items
     *    (`playlistVideoRenderer`), the rest arrive via the
     *    `youtubei/v1/browse` endpoint (one POST per page of ~100).
     *  - MODERN (2025+): the initial HTML embeds ONLY playlist metadata — no
     *    video list at all. The full ordered list is served by the
     *    `youtubei/v1/next` endpoint's playlist panel (`playlistPanelVideoRenderer`
     *    items), paginated the same way.
     *
     * A successful response is authoritative — a private/unavailable playlist
     * returns an info with an empty video list (no RSS fallback, that's the
     * answer). Only when the page can't be fetched/parsed or the modern panel
     * fetch fails outright does the RSS feed get tried (it returns the newest
     * ~15 videos — a degraded fallback, never the primary path).
     */
    private fun fetchPlaylistInfo(playlistId: String): PlaylistPageParser.PlaylistInfo? {
        val pageHtml = fetchPlaylistPageHtml(playlistId)
        val first = pageHtml?.let { PlaylistPageParser.parsePage(it) }

        if (first != null && first.videos.isNotEmpty()) {
            // Classic format: the list is embedded. Walk the continuation
            // chain: every browse page yields the next batch of videos AND
            // the token for the page after it. A LinkedHashMap keeps the
            // playlist order while deduplicating (defensive — the pages never
            // actually repeat items). A failed page stops the walk (cache
            // what we have rather than failing the whole playlist).
            val apiKey = PlaylistPageParser.innertubeApiKey(pageHtml)
            val context = PlaylistPageParser.innertubeContext(pageHtml)
            val all = LinkedHashMap<String, MediaVideo>()
            first.videos.forEach { all[it.videoId] = it }

            var token = PlaylistPageParser.firstContinuationToken(pageHtml)
            var pages = 0
            while (token != null && pages < MAX_PLAYLIST_PAGES) {
                pages++
                val page = fetchPlaylistContinuation(token, apiKey, context) ?: break
                page.videos.forEach { v -> if (v.videoId !in all) all[v.videoId] = v }
                token = page.nextToken
            }
            return PlaylistPageParser.PlaylistInfo(first.title, all.values.toList())
        }

        if (first != null) {
            // Modern format: the page parsed fine (title + metadata) but
            // carries NO embedded video list — a PUBLIC playlist looks exactly
            // like this now, so this is NOT "private". Fetch the full list
            // from the /next endpoint's playlist panel. A reached panel with
            // an empty list is the authoritative private/unavailable answer;
            // only a failed panel fetch falls back to RSS.
            val apiKey = PlaylistPageParser.innertubeApiKey(pageHtml)
            val context = PlaylistPageParser.innertubeContext(pageHtml)
            if (context != null) {
                fetchPlaylistPanelViaNext(playlistId, apiKey, context)?.let { return it }
            }
        }

        // Page fetch or parse failed, or the modern panel fetch failed
        // outright — last resort: RSS (newest ~15 videos).
        return fetchPlaylistRss(playlistId)
    }

    /**
     * Modern playlist pages (2025+ page format) embed NO video list in the
     * initial HTML — only playlist metadata. The full ordered list is served
     * by the `/youtubei/v1/next` endpoint's playlist panel
     * (`playlistPanelVideoRenderer` items), which this walks to the end using
     * the same continuation pattern as the classic browse walk. Returns null
     * only when the endpoint can't be reached or the response has no playlist
     * panel; a reached panel with an empty list is authoritative (private /
     * unavailable playlist).
     */
    private fun fetchPlaylistPanelViaNext(
        playlistId: String,
        apiKey: String?,
        context: JSONObject?
    ): PlaylistPageParser.PlaylistInfo? {
        val first = fetchNextPanel(playlistId, apiKey, context, continuation = null)
            ?: return null
        val all = LinkedHashMap<String, MediaVideo>()
        first.videos.forEach { all[it.videoId] = it }

        var token = first.nextToken
        var pages = 0
        while (token != null && pages < MAX_PLAYLIST_PAGES) {
            pages++
            val page = fetchNextPanel(playlistId, apiKey, context, continuation = token) ?: break
            page.videos.forEach { v -> if (v.videoId !in all) all[v.videoId] = v }
            token = page.nextToken
        }
        return PlaylistPageParser.PlaylistInfo(first.title, all.values.toList())
    }

    /**
     * One `/youtubei/v1/next` request: the playlist's full video panel (when
     * [continuation] is null) or the panel's next page (when it isn't). Null
     * on any network/parse failure (the walk stops, keeping what it has).
     */
    private fun fetchNextPanel(
        playlistId: String,
        apiKey: String?,
        context: JSONObject?,
        continuation: String?
    ): PlaylistPageParser.PlaylistPanel? {
        val url = "https://www.youtube.com/youtubei/v1/next" +
            (if (apiKey != null) "?key=$apiKey" else "")
        val body = JSONObject()
            .put("context", context ?: JSONObject())
            .apply {
                if (continuation != null) put("continuation", continuation)
                else put("playlistId", playlistId)
            }
            .toString()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
                setRequestProperty("Referer", "https://www.youtube.com/")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val resp = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            PlaylistPageParser.parsePlaylistPanel(resp)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Fetches the playlist page HTML with a DESKTOP UA (full ytInitialData). */
    private fun fetchPlaylistPageHtml(playlistId: String): String? {
        val pageUrl = "https://www.youtube.com/playlist?list=$playlistId"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * One `youtubei/v1/browse` continuation request: POSTs the page's own
     * innertube context + token and parses the next batch. Null on any
     * network/parse failure (the walk stops, keeping what it has).
     */
    private fun fetchPlaylistContinuation(
        token: String,
        apiKey: String?,
        context: JSONObject?
    ): PlaylistPageParser.ContinuationPage? {
        val url = "https://www.youtube.com/youtubei/v1/browse" +
            (if (apiKey != null) "?key=$apiKey" else "")
        val body = JSONObject()
            .put("context", context ?: JSONObject())
            .put("continuation", token)
            .toString()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
                setRequestProperty("Referer", "https://www.youtube.com/")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val resp = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            PlaylistPageParser.parseContinuationPage(resp)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** RSS fallback for playlists: `feeds/videos.xml?playlist_id=` (newest ~15). */
    private fun fetchPlaylistRss(playlistId: String): PlaylistPageParser.PlaylistInfo? {
        val url = "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/atom+xml")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val videos = YouTubeRssParser.parse(body, emptySet())
            val title = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() }
            PlaylistPageParser.PlaylistInfo(title ?: "", videos)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "MediaRepository"
        const val PREFS_NAME = "media_prefs"
        const val KEY_CHANNELS = "saved_channels"
        const val KEY_SELECTED_CHANNEL = "selected_channel"
        const val KEY_PLAYLISTS = "saved_playlists"
        const val KEY_MEDIA_NOTIFICATIONS_ENABLED = "media_notifications_enabled"
        const val KEY_NOTIFIED_VIDEOS = "notified_video_ids"
        const val KEY_UPDATES_HISTORY = "updates_history"
        const val KEY_SEEN_UPDATE_IDS = "seen_update_ids"
        const val KEY_FEED_FILTER = "feed_filter"
        const val MAX_NOTIFIED_VIDEOS = 200
        // Safety cap on continuation pages (~100 videos each → up to 10 000
        // videos, far beyond YouTube's 5 000-video playlist maximum).
        const val MAX_PLAYLIST_PAGES = 100
        const val DEFAULT_MEDIA_NOTIFICATIONS_ENABLED = true

        // A DESKTOP UA gets the full server-rendered page (a mobile UA makes
        // YouTube serve a reduced page without the playlist's ytInitialData,
        // which silently fell back to the ~15-video RSS feed).
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

        val DEFAULT_CHANNEL = SavedChannel(
            channelId = "UC2cX3SmsdWsrRS8t_5zvzEw", // Safina Society (@SafinaSociety)
            displayName = "Safina Society",
            sourceRef = "@SafinaSociety"
        )
    }
}
