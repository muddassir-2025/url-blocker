package com.muddassir.clearview.media.data

import android.util.Log
import com.muddassir.clearview.media.model.LiveStreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a live-stream SOURCE's (Makkah / Madinah) CURRENT live broadcast
 * video id, so the Live tab can play it with the same in-app IFrame player as
 * regular videos. Discovery is kept strictly separate from playback: this
 * object only ever produces a validated video id; the player never scrapes.
 *
 * The OFFICIAL YouTube channel is the stable identity. The airing broadcast's
 * video id changes over time, but the channel id never does, so
 * [LiveStreamConfig] stores ONLY channel identifiers and the current broadcast
 * is DISCOVERED here at runtime. No video id is hardcoded — a new broadcast
 * never requires an app update.
 *
 * DISCOVERY (per source, strongest first):
 *
 *  1. The channel's `/live` page — player-response LIVE MARKERS
 *     (`isLive` / `isLiveNow` / `liveStreamability`). Fastest path, used by
 *     the full page variant.
 *  2. The same page's `<link rel="canonical">`. On a `/live` URL the
 *     canonical always names the broadcast (`…/watch?v=<id>`); on a channel
 *     page it names the channel (no id → not live). This SEO tag survives the
 *     reduced variants that strip the player response.
 *  3. The FIRST videoId anywhere in the `/live` document. Reduced variants
 *     embed the airing broadcast's id as the page's first videoId even when
 *     the player response is an empty stub and there is no canonical
 *     (observed on device). Gated on the page presenting a player response so
 *     a channel-home redirect (no broadcast) can never feed a featured VOD in
 *     here.
 *  4. The channel's `/streams` page (channel video data): the first video id
 *     the page presents. Only ever lists the channel's own videos, so channel
 *     ownership is implied; liveness is still validated on the `/watch` page.
 *  5. LAST KNOWN (Method 5): the per-channel persisted video id from
 *     [LiveStreamCacheStore], only when every discovery method above failed.
 *     NEVER played blind: it is re-validated on its `/watch` page (still
 *     live, correct channel, playable) before use. When a new broadcast
 *     starts, a successful resolution replaces the stored id automatically.
 *
 * VALIDATION (mandatory for every accepted candidate, incl. the cached one):
 * the candidate's `/watch` page must confirm it is CURRENTLY LIVE (live
 * markers in the player response), the candidate IS that page's own video, it
 * belongs to the EXPECTED OFFICIAL channel (owner channelId — Makkah can never
 * accept a Madinah id or vice versa; no cross-channel substitution, ever),
 * and it is PLAYABLE (playabilityStatus OK).
 *
 * PAGE VARIANTS & RESTRICTED MODE: YouTube serves different HTML for the same
 * URL depending on network/IP/region/cookies. DNS filters such as CleanBrowsing
 * Family Filter additionally force YouTube Restricted Mode by mapping
 * www.youtube.com to Google's restricted-mode IPs — Restricted Mode suppresses
 * live streams SERVER-SIDE (the `/live` page returns ~795 KB with a player
 * response whose playabilityStatus is ERROR, no live markers, no canonical).
 * [looksRestrictedMode] detects that signature and reports
 * [LiveResolveResult.blockedByFilter] so the UI can explain the real cause.
 * It cannot be fixed purely in code (the WebView embed player cannot bypass
 * the system DNS), but detection makes the failure obvious and actionable.
 *
 * Verified: both target channels air live broadcasts that resolve correctly
 * when fetched with a DESKTOP user agent — a mobile UA makes YouTube serve a
 * page without the player response (same reduced shape Restricted Mode
 * produces).
 */
object LiveStreamResolver {

    private const val TAG = "LiveStreamResolver"
    private const val CACHE_TTL_MS = 60_000L

    private val VIDEO_ID = Regex("\"videoId\"\\s*:\\s*\"([0-9A-Za-z_-]{11})\"")

    // The `isLive` / `isLiveNow` flags — the classic "currently airing" marker.
    private val IS_LIVE = Regex("\"isLive\"\\s*:\\s*true|\"isLiveNow\"\\s*:\\s*true")

    // Structural live markers accepted inside the player response block.
    // `liveStreamability` is the live-playback config YouTube MUST emit to
    // start a live stream — it survives reduced variants that drop the isLive
    // flag. `isLiveNow:true` rejects upcoming broadcasts (they carry
    // liveBroadcastDetails with isLiveNow:false).
    private val LIVE_MARKERS = listOf(
        Regex("\"isLive\"\\s*:\\s*true"),
        Regex("\"isLiveNow\"\\s*:\\s*true"),
        Regex("\"liveStreamability\"")
    )

    // Tier 2: the page's canonical link — plain HTML in the <head>, so it
    // survives the reduced variants. www/m subdomains both possible.
    private val CANONICAL_WATCH = Regex(
        """<link[^>]*rel="canonical"[^>]*href="https://(?:www\.|m\.)?youtube\.com/watch\?v=([0-9A-Za-z_-]{11})""""
    )
    private val CANONICAL_WATCH_ESCAPED = Regex(
        """rel="canonical"[^>]*href="https:\\/\\/(?:www\.|m\.)?youtube\.com\\/watch\?v=([0-9A-Za-z_-]{11})""""
    )

    // Playability status inside the player response ("OK" = playable; any
    // other value = blocked/unavailable — the Restricted-Mode signature).
    private val PLAYABILITY_STATUS =
        Regex("\"playabilityStatus\"\\s*:\\s*\\{[^}]*?\"status\"\\s*:\\s*\"([A-Z_]+)\"")

    // Ownership signals inside the player response block. `ownerChannelId`
    // (microformat) is the explicit uploader; the block's first `channelId`
    // (videoDetails.channelId) is the same channel.
    private val OWNER_CHANNEL = Regex("\"ownerChannelId\"\\s*:\\s*\"(UC[0-9A-Za-z_-]{22})\"")
    private val CHANNEL_ID = Regex("\"channelId\"\\s*:\\s*\"(UC[0-9A-Za-z_-]{22})\"")

    // channelId -> (videoId, resolvedAt). Only SUCCESSFUL, VALIDATED
    // resolutions are cached (short TTL) so repeat opens don't re-fetch; a
    // retry (forceRefresh) always re-fetches so a newly started broadcast is
    // picked up immediately. ConcurrentHashMap because the singleton is shared
    // across IO threads.
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Result of a resolution attempt. [videoId] is non-null ONLY when a
     * candidate passed full validation. When it is null and [blockedByFilter]
     * is true, the served page matched the Restricted-Mode/DNS-filter
     * signature — retrying will not help until the filter exempts YouTube.
     */
    data class LiveResolveResult(
        val videoId: String?,
        val blockedByFilter: Boolean = false
    )

    /**
     * Resolves [source]'s current live broadcast video id (validated), or
     * null when no broadcast is airing / the pages can't be fetched.
     * [forceRefresh] bypasses the short-lived in-memory cache (used by retry).
     * Successful resolutions are persisted per channel in [cacheStore], and
     * the persisted id becomes the last-known fallback for later attempts.
     */
    suspend fun resolveLiveVideoId(
        source: LiveStreamSource,
        cacheStore: LiveStreamCacheStore,
        forceRefresh: Boolean = false
    ): LiveResolveResult = withContext(Dispatchers.IO) {
        val channelId = source.channelId
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cache[channelId]?.let { (id, at) ->
                if (now - at < CACHE_TTL_MS) {
                    Log.d(TAG, "LIVE_CACHE_HIT channelId=$channelId videoId=$id")
                    return@withContext LiveResolveResult(videoId = id)
                }
            }
        }

        val liveHtml = fetchHtml("https://www.youtube.com/channel/$channelId/live")
        if (liveHtml == null) {
            Log.w(TAG, "LIVE_FETCH_NULL channelId=$channelId")
            return@withContext LiveResolveResult(videoId = null)
        }
        logPageDiagnostics(channelId, liveHtml)

        // Restricted-Mode / DNS-filter signature — fail fast with the reason,
        // before spending more requests on pages the filter has already
        // emptied. Tiers 1-3 still run first (cheap: they only re-read the
        // page already fetched) in case the page is merely odd, not filtered.
        val filtered = looksRestrictedMode(liveHtml)

        // Tier 1: player-response live markers.
        extractLiveVideoId(liveHtml)?.let { candidate ->
            if (confirm(candidate, channelId)) {
                return@withContext accept(channelId, candidate, "LIVE_RESOLVED", cacheStore)
            }
        }

        // Tier 2: canonical watch link.
        canonicalVideoId(liveHtml)?.let { candidate ->
            if (confirm(candidate, channelId)) {
                return@withContext accept(channelId, candidate, "LIVE_RESOLVED_VIA_CANONICAL", cacheStore)
            }
        }

        // Restricted-Mode / DNS-filter signature — fail fast with the reason.
        // Tiers 1-2 already found nothing on this page shape, and the /watch
        // pages under the filter are equally stripped, so further fetches
        // cannot help: report the block instead of burning requests.
        if (filtered) {
            Log.w(TAG, "LIVE_BLOCKED_BY_FILTER channelId=$channelId (Restricted Mode signature)")
            return@withContext LiveResolveResult(videoId = null, blockedByFilter = true)
        }

        // Tier 3: first videoId in the whole document (reduced variants).
        if (liveHtml.contains("ytInitialPlayerResponse")) {
            firstVideoIdInDoc(liveHtml)?.let { candidate ->
                if (confirm(candidate, channelId)) {
                    return@withContext accept(channelId, candidate, "LIVE_RESOLVED_VIA_PAGE_ID", cacheStore)
                }
            }
        }

        // Tier 4: channel video data (/streams page) — the first video id it
        // presents, validated. Lists only the channel's own videos.
        val streamsHtml = fetchHtml("https://www.youtube.com/channel/$channelId/streams")
        if (streamsHtml != null) {
            firstVideoIdInDoc(streamsHtml)?.let { candidate ->
                if (confirm(candidate, channelId)) {
                    return@withContext accept(channelId, candidate, "LIVE_RESOLVED_VIA_STREAMS", cacheStore)
                }
            }
        }

        // Tier 5 (last resort): the persisted last-known id — validated
        // against its /watch page, never blindly played.
        cacheStore.lastVideoId(channelId)?.let { known ->
            if (confirm(known, channelId)) {
                return@withContext accept(channelId, known, "LIVE_RESOLVED_VIA_CACHED", cacheStore)
            }
            Log.w(TAG, "LIVE_CACHED_STALE channelId=$channelId videoId=$known")
        }

        Log.w(TAG, "LIVE_NOT_ACTIVE channelId=$channelId (no validated live broadcast)")
        LiveResolveResult(videoId = null)
    }

    /** Fetches the candidate's /watch page and runs full validation. */
    private fun confirm(candidate: String, channelId: String): Boolean {
        val watchHtml = fetchWatchPage(candidate) ?: return false
        return validateWatchPage(watchHtml, candidate, channelId)
    }

    /** Persists a validated resolution (per channel) and caches it. */
    private fun accept(
        channelId: String,
        videoId: String,
        tag: String,
        cacheStore: LiveStreamCacheStore
    ): LiveResolveResult {
        cacheStore.rememberResolved(channelId, videoId)
        cache[channelId] = videoId to System.currentTimeMillis()
        Log.d(TAG, "$tag channelId=$channelId videoId=$videoId")
        return LiveResolveResult(videoId = videoId)
    }

    /**
     * MANDATORY validation for a candidate, run on its `/watch` page:
     *
     *  1. CURRENTLY LIVE — the player response carries live markers AND its
     *     own videoId names the candidate (a watch page's player response
     *     always names the video itself; a VOD never carries live markers).
     *  2. PLAYABLE — playabilityStatus, when present, must be "OK" (a
     *     non-OK status is exactly what Restricted Mode / geo-blocks serve).
     *  3. OWNERSHIP — the owner channelId, when determinable, must be the
     *     EXPECTED official channel. This is what makes cross-channel
     *     substitution impossible: Makkah can never accept a Madinah id and
     *     vice versa.
     *
     * Pure (unit-testable); [confirm] feeds it fetched pages.
     */
    fun validateWatchPage(watchHtml: String, videoId: String, expectedChannelId: String): Boolean {
        if (extractLiveVideoId(watchHtml) != videoId) return false
        val status = playabilityStatus(watchHtml)
        if (status != null && status != "OK") return false
        val owner = ownerChannelId(watchHtml)
        return owner == null || owner == expectedChannelId
    }

    /**
     * True when the page matches the Restricted-Mode / DNS-filter signature:
     * a player response IS present, the video is NOT playable (the EXACT
     * `playabilityStatus.status` "ERROR" — the status Restricted Mode serves),
     * there are no live markers, and no canonical watch link. Restricting to
     * the exact ERROR status keeps other non-playable states (LOGIN_REQUIRED,
     * age/geo blocks) from being misreported as a DNS-filter block. Pure,
     * unit-tested.
     */
    fun looksRestrictedMode(html: String): Boolean {
        if (!html.contains("ytInitialPlayerResponse")) return false
        if (IS_LIVE.containsMatchIn(html) || html.contains("liveStreamability")) return false
        if (canonicalVideoId(html) != null) return false
        return playabilityStatus(html) == "ERROR"
    }

    /** The playabilityStatus value inside the player response, or null. */
    fun playabilityStatus(html: String): String? =
        PLAYABILITY_STATUS.find(playerResponseBlock(html))?.groupValues?.get(1)

    /** The uploader channel id from the player response block, or null. */
    fun ownerChannelId(html: String): String? {
        val block = playerResponseBlock(html)
        OWNER_CHANNEL.find(block)?.let { return it.groupValues[1] }
        return CHANNEL_ID.find(block)?.groupValues?.get(1)
    }

    private fun fetchLivePage(channelId: String): String? =
        fetchHtml("https://www.youtube.com/channel/$channelId/live")

    private fun fetchWatchPage(videoId: String): String? =
        // Validation fetch uses tighter timeouts: it only runs when a
        // candidate is being checked, so a slow /watch page must not double
        // the user's wait.
        fetchHtml("https://www.youtube.com/watch?v=$videoId", 10_000, 12_000)

    private fun fetchHtml(
        url: String,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000
    ): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                // CRITICAL: a MOBILE UA makes YouTube serve a reduced page that
                // omits the live player response entirely (no videoId → wrongly
                // reported as not live). A DESKTOP UA gets the full page with
                // ytInitialPlayerResponse.videoDetails.
                setRequestProperty("Accept", "text/html")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
                )
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "LIVE_FETCH_FAILED url=$url: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** The live video id named by the page's canonical link, or null. */
    fun canonicalVideoId(html: String): String? {
        CANONICAL_WATCH.find(html)?.let { return it.groupValues[1] }
        return CANONICAL_WATCH_ESCAPED.find(html)?.groupValues?.get(1)
    }

    /** The first videoId anywhere in the document, or null. */
    fun firstVideoIdInDoc(html: String): String? =
        VIDEO_ID.find(html)?.groupValues?.get(1)

    /**
     * Pure extraction: the live video id from the player response block, but
     * ONLY when the block carries a live marker (`isLive`/`isLiveNow` true,
     * or `liveStreamability`). These fields only exist in a LIVE player
     * response; an upcoming broadcast has isLiveNow:false and never matches.
     * The block's FIRST videoId is always `videoDetails.videoId` — the main
     * video, never a related one.
     */
    fun extractLiveVideoId(html: String): String? {
        val block = playerResponseBlock(html)
        if (LIVE_MARKERS.none { it.containsMatchIn(block) }) return null
        return VIDEO_ID.find(block)?.groupValues?.get(1)
    }

    private fun logPageDiagnostics(channelId: String, html: String) {
        val hasPlayerResponse = html.contains("ytInitialPlayerResponse")
        val hasIsLiveMarker = IS_LIVE.containsMatchIn(html)
        val hasLiveStreamability = html.contains("liveStreamability")
        Log.d(
            TAG,
            "LIVE_PAGE channelId=$channelId len=${html.length} " +
                "hasPlayerResponse=$hasPlayerResponse hasIsLiveMarker=$hasIsLiveMarker " +
                "hasLiveStreamability=$hasLiveStreamability canonical=${canonicalVideoId(html)} " +
                "firstVideoId=${firstVideoIdInDoc(html)} status=${playabilityStatus(html)}"
        )
    }

    private fun playerResponseBlock(html: String): String {
        val start = html.indexOf("ytInitialPlayerResponse")
        return if (start >= 0) {
            val end = html.indexOf("</script>", start)
            if (end > start) html.substring(start, end) else html.substring(start)
        } else {
            // No player response → nothing scoped; callers gate on markers
            // before trusting any id, so an empty block is the safe outcome.
            ""
        }
    }
}
