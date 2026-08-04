package com.muddassir.clearview.media.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves a video's duration (seconds) from its watch page — the RSS feed has
 * no duration field, and YouTube's public oEmbed endpoint omits it too, so the
 * watch page's `ytInitialPlayerResponse.videoDetails.lengthSeconds` (embedded
 * JSON) is the key-less source, using the same page-scrape technique as
 * [ChannelIdResolver] / [LiveStreamResolver] / [ShortsIdResolver].
 *
 * Results are cached in memory AND persisted to a dedicated disk cache
 * (video_durations_cache.json) so a duration is fetched exactly once per video
 * and reused across restarts — the first refresh after a restart never
 * re-fetches watch pages for videos resolved before. The per-channel feed
 * cache additionally carries the values onto feed cards (see
 * [MediaVideo.durationSeconds]).
 *
 * [extractDurationSeconds], [encodeDiskCache] and [decodeDiskCache] are pure
 * functions (unit-testable); [fetchDuration] does the network call and returns
 * null on any failure (the feed then simply shows no time badge — durations
 * are best-effort enrichment).
 */
object VideoDurationResolver {

    private const val TAG = "VideoDurationResolver"

    // Successful resolutions persist for a month — durations essentially never
    // change for regular videos, and this is what lets the disk cache survive
    // restarts without re-fetching.
    internal const val SUCCESS_TTL_MS = 30L * 24 * 60 * 60_000L
    // Failures (0L) are cached too — but briefly — so a video that ALWAYS fails
    // (age-restricted, consent-walled, region-locked) isn't re-fetched on every
    // refresh, while a transient failure (or a live stream that later becomes
    // a VOD) still gets retried after an hour.
    internal const val NEGATIVE_TTL_MS = 60 * 60_000L

    private const val DISK_FILE_NAME = "video_durations_cache.json"
    // Cap so the file can't grow unbounded (entries beyond the newest are
    // dropped on the next write; a video's duration doesn't change, so the
    // newest-capped set is all that ever matters).
    private const val MAX_DISK_ENTRIES = 500
    // Resolutions land in a burst (up to 10 per channel per refresh); debounce
    // the disk write so the whole burst produces ONE file write instead of 10N.
    private const val FLUSH_DELAY_MS = 400L

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    @Volatile
    private var diskFile: File? = null

    // Load-once guard: MediaRepository is constructed from several places
    // (Media tab, workers…), but the disk file only needs one read per process.
    @Volatile
    private var initialized = false

    // Coalesces disk writes: the flag is set when a flush is scheduled/queued,
    // so a burst of resolutions triggers exactly one persist.
    private val flushQueued = AtomicBoolean(false)

    private val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "duration-cache-flusher").apply { isDaemon = true }
    }

    /**
     * Wires the resolver to its on-disk cache and preloads previously resolved
     * durations (pruned by TTL). Idempotent and load-once — safe to call from
     * every [MediaRepository] construction.
     */
    @Synchronized
    fun init(context: Context) {
        val file = File(context.applicationContext.filesDir, DISK_FILE_NAME)
        diskFile = file
        if (initialized) return
        initialized = true
        val loaded = decodeDiskCache(
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull(),
            System.currentTimeMillis()
        )
        if (loaded.isNotEmpty()) {
            cache.putAll(loaded)
            Log.d(TAG, "DISK_LOADED entries=${loaded.size}")
        }
    }

    /**
     * Returns the duration for [videoId] (seconds), or null when unknown /
     * unresolvable. Successful and failed lookups are remembered (in memory +
     * on disk) so consistent failures don't hammer YouTube on every refresh.
     */
    suspend fun fetchDuration(videoId: String): Long? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache[videoId]?.let { (seconds, at) ->
            val ttl = if (seconds > 0L) SUCCESS_TTL_MS else NEGATIVE_TTL_MS
            if (now - at < ttl) {
                Log.d(TAG, "DURATION_CACHE_HIT videoId=$videoId seconds=$seconds")
                return@withContext seconds.takeIf { it > 0L }
            }
        }
        val html = fetchWatchPage(videoId)
        if (html == null) {
            Log.w(TAG, "DURATION_FETCH_NULL videoId=$videoId")
            cacheResult(videoId, 0L, now)
            return@withContext null
        }
        val seconds = extractDurationSeconds(html)
        if (seconds != null) {
            cacheResult(videoId, seconds, now)
            Log.d(TAG, "DURATION_RESOLVED videoId=$videoId seconds=$seconds")
        } else {
            cacheResult(videoId, 0L, now)
        }
        seconds
    }

    /** Updates the in-memory cache and queues a (debounced) disk write. */
    private fun cacheResult(videoId: String, seconds: Long, at: Long) {
        cache[videoId] = seconds to at
        if (flushQueued.compareAndSet(false, true)) {
            flusher.schedule({
                flushQueued.set(false)
                persistToDisk()
            }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Writes the cache to disk, capped at [MAX_DISK_ENTRIES] newest first. */
    private fun persistToDisk() {
        val file = diskFile ?: return
        val capped = cache.entries
            .sortedByDescending { it.value.second }
            .take(MAX_DISK_ENTRIES)
            .associate { it.key to it.value }
        try {
            file.writeText(encodeDiskCache(capped), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "DISK_WRITE_FAILED: ${e.message}")
        }
    }

    private fun fetchWatchPage(videoId: String): String? {
        val url = "https://www.youtube.com/watch?v=$videoId"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html")
                // A mobile UA serves a reduced page without the player response
                // (same as LiveStreamResolver) — desktop UA is required.
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
                )
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "DURATION_FETCH_FAILED videoId=$videoId: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Pure extraction step: the video's length in seconds from the watch-page
     * HTML, or null when the player response is absent / malformed. Scoped to
     * the ytInitialPlayerResponse block so a stray lengthSeconds from a related
     * video elsewhere on the page can't be picked up.
     *
     * Deliberately a plain string scan (no regex): the watch page's JSON is
     * predictable (`"lengthSeconds":"NNNN"` or `"lengthSeconds":NNNN`), and
     * string ops sidestep escaping issues entirely. Each candidate marker is
     * only accepted when it's followed (after optional whitespace) by a colon
     * and a numeric value, so a stray marker elsewhere in the block can't win.
     */
    fun extractDurationSeconds(html: String): Long? {
        val start = html.indexOf("ytInitialPlayerResponse")
        val block = if (start >= 0) {
            val end = html.indexOf("</script>", start)
            if (end > start) html.substring(start, end) else html.substring(start)
        } else {
            html
        }
        val marker = "\"lengthSeconds\""
        var searchFrom = 0
        while (true) {
            val i = block.indexOf(marker, searchFrom)
            if (i < 0) return null
            val rest = block.substring(i + marker.length)
            // Only accept the marker if the next non-whitespace char is ':'.
            val firstNonSpace = rest.indexOfFirst { !it.isWhitespace() }
            if (firstNonSpace >= 0 && rest[firstNonSpace] == ':') {
                val digits = rest
                    .substring(firstNonSpace + 1)
                    .trimStart()
                    .trimStart('"')
                    .takeWhile { it.isDigit() }
                if (digits.isNotEmpty()) return digits.toLongOrNull()
            }
            // Stray/malformed marker — keep scanning for the real one.
            searchFrom = i + marker.length
        }
    }

    /**
     * Pure: encodes the cache (videoId → seconds + resolved-at) as JSON for
     * disk persistence.
     */
    fun encodeDiskCache(entries: Map<String, Pair<Long, Long>>): String {
        val arr = JSONArray()
        entries.forEach { (id, pair) ->
            arr.put(
                JSONObject()
                    .put("videoId", id)
                    .put("seconds", pair.first)
                    .put("at", pair.second)
            )
        }
        return JSONObject().put("entries", arr).toString()
    }

    /**
     * Pure: decodes the persisted cache, dropping entries past their TTL
     * (successes live for [SUCCESS_TTL_MS], failures for [NEGATIVE_TTL_MS]) and
     * entries with a blank video id. Empty map on blank/corrupt input.
     */
    fun decodeDiskCache(json: String?, now: Long): Map<String, Pair<Long, Long>> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("entries") ?: return emptyMap()
            val out = HashMap<String, Pair<Long, Long>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val videoId = o.optString("videoId", "")
                if (videoId.isBlank()) continue
                val seconds = o.optLong("seconds", 0L)
                val at = o.optLong("at", 0L)
                val ttl = if (seconds > 0L) SUCCESS_TTL_MS else NEGATIVE_TTL_MS
                if (now - at < ttl) out[videoId] = seconds to at
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
