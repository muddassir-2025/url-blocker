package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.MediaVideo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/**
 * Pure parser for a YouTube playlist page's embedded `ytInitialData` JSON and
 * the `youtubei/v1/browse` continuation responses that paginate it. No
 * network, no Android dependencies — unit-testable on the JVM.
 *
 * The playlist page embeds the whole playlist state as JSON; this parser
 * walks it (recursively, in document order — which IS the playlist order) and
 * pulls the playlist title plus every `playlistVideoRenderer` entry. A
 * private / unavailable playlist has no video list at all, which the caller
 * (MediaRepository) turns into a friendly error.
 *
 * YouTube serves only the FIRST page of a playlist's videos in the initial
 * HTML (the rest are fetched via a continuation token through the browse
 * endpoint). [firstContinuationToken] + [parseContinuationPage] let the
 * repository walk every page, so an imported playlist is never truncated at
 * the initial ~100 items (or the RSS fallback's ~15).
 */
object PlaylistPageParser {

    /** Parsed playlist: title + videos in playlist order. */
    data class PlaylistInfo(
        val title: String,
        val videos: List<MediaVideo>
    )

    /** One browse-continuation response: the next batch of videos + next token. */
    data class ContinuationPage(
        val videos: List<MediaVideo>,
        /** Token for the NEXT continuation page, or null when this was the last. */
        val nextToken: String?
    )

    /**
     * One `/youtubei/v1/next` playlist-panel response (the modern page format
     * that no longer embeds the video list in the initial HTML): the videos in
     * playlist order plus the token for the next panel page.
     */
    data class PlaylistPanel(
        val title: String,
        val videos: List<MediaVideo>,
        /** Token for the NEXT panel page, or null when this was the last. */
        val nextToken: String?
    )

    private val INITIAL_DATA = Pattern.compile(
        """ytInitialData\s*=\s*(\{.*?\});</script>""",
        Pattern.DOTALL
    )

    /**
     * Extracts `ytInitialData` from the raw page HTML and parses the playlist
     * title + ordered videos. Returns null when the JSON can't be found or
     * parsed (not a playlist page / bot-walled page). A valid page with a
     * private playlist returns an info with an empty [PlaylistInfo.videos].
     */
    fun parsePage(html: String): PlaylistInfo? {
        val json = extractInitialData(html) ?: return null
        return try {
            parseInitialData(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    private fun extractInitialData(html: String): String? {
        val matcher = INITIAL_DATA.matcher(html)
        if (!matcher.find()) return null
        return matcher.group(1)
    }

    /**
     * The FIRST continuation token embedded in the playlist page's
     * `ytInitialData` (drives the browse pagination), or null when the whole
     * playlist already fit on the first page.
     */
    fun firstContinuationToken(html: String): String? {
        val json = extractInitialData(html) ?: return null
        return try {
            continuationToken(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses one `youtubei/v1/browse` or `youtubei/v1/next` continuation
     * response (plain JSON, with an optional `)]}'` JSONP guard stripped): the
     * next batch of videos in playlist order plus the token for the page after
     * it. Null when the body isn't a parseable continuation response.
     */
    fun parseContinuationPage(body: String): ContinuationPage? {
        val cleaned = body.trim().removePrefix(")]}'").trim()
        if (cleaned.isEmpty()) return null
        return try {
            val root = JSONObject(cleaned)
            // Not a continuation response — treat as unparseable. The action
            // container is `onResponseReceivedActions` for youtubei/v1/browse
            // walks and `onResponseReceivedEndpoints` for youtubei/v1/next
            // playlist-panel pages (both carry appendContinuationItemsAction).
            if (!root.has("onResponseReceivedActions") &&
                !root.has("onResponseReceivedEndpoints")
            ) return null
            val entries = ArrayList<JSONObject>()
            collect(root, entries)
            ContinuationPage(
                videos = entries.mapNotNull { parseVideo(it) },
                nextToken = continuationToken(root)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** The innertube API key embedded in the page (used by the browse POST). */
    fun innertubeApiKey(html: String): String? =
        Regex(""""INNERTUBE_API_KEY"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)

    /**
     * The innertube request context embedded in the page (client name /
     * version etc.), parsed from the balanced JSON object after
     * `"INNERTUBE_CONTEXT":`. Null when absent or unparseable.
     */
    fun innertubeContext(html: String): JSONObject? {
        val marker = "\"INNERTUBE_CONTEXT\""
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val braceStart = html.indexOf('{', idx + marker.length)
        if (braceStart < 0) return null
        val json = extractBalancedObject(html, braceStart) ?: return null
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses one `/youtubei/v1/next` PLAYLIST-PANEL response (the page format
     * YouTube uses since 2025+ for playlist pages that no longer embed the
     * video list in the initial HTML). Handles BOTH response shapes:
     *
     *  - Page 1: the full wrapper `contents.twoColumnWatchNextResults.playlist
     *    .playlist` carrying the ordered `playlistPanelVideoRenderer` items
     *    (same fields as `playlistVideoRenderer`) plus the panel's own
     *    continuation token when the playlist spans more than one panel page.
     *  - Continuation pages: `onResponseReceivedEndpoints[].
     *    appendContinuationItemsAction.continuationItems` with the next batch
     *    of items (no wrapper, no title — the caller keeps page 1's title).
     *
     * Null when the body isn't a parseable /next response or contains neither
     * shape.
     */
    fun parsePlaylistPanel(body: String): PlaylistPanel? {
        val cleaned = body.trim().removePrefix(")]}'").trim()
        if (cleaned.isEmpty()) return null
        return try {
            val root = JSONObject(cleaned)
            val panel = root.optJSONObject("contents")
                ?.optJSONObject("twoColumnWatchNextResults")
                ?.optJSONObject("playlist")
                ?.optJSONObject("playlist")
            if (panel != null) {
                // Page 1: the wrapper with the playlist title.
                val entries = ArrayList<JSONObject>()
                panel.optJSONArray("contents")?.let { contents ->
                    for (i in 0 until contents.length()) {
                        contents.optJSONObject(i)
                            ?.optJSONObject("playlistPanelVideoRenderer")
                            ?.let { entries.add(it) }
                    }
                }
                PlaylistPanel(
                    title = textOf(panel.optJSONObject("titleText"))
                        .ifBlank { textOf(panel.optJSONObject("title")) },
                    videos = entries.mapNotNull { parseVideo(it) },
                    nextToken = panelContinuationToken(panel)
                )
            } else {
                // Continuation page: the appended batch under the action
                // container (browse or next). No title here.
                if (!root.has("onResponseReceivedActions") &&
                    !root.has("onResponseReceivedEndpoints")
                ) return null
                val entries = ArrayList<JSONObject>()
                collect(root, entries)
                PlaylistPanel(
                    title = "",
                    videos = entries.mapNotNull { parseVideo(it) },
                    nextToken = walkForToken(root)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The first continuation token in any YouTube JSON tree: either a
     * `nextContinuationData.continuation` (initial page) or a
     * `continuationCommand.token` (browse responses).
     *
     * The INITIAL page is searched for the token INSIDE the playlist's own
     * `playlistVideoListRenderer` FIRST — the page's header or unrelated
     * shelves may carry continuation commands of their own, and grabbing one
     * of those would paginate the wrong thing. Browse responses (plain
     * continuation items, no playlistVideoListRenderer wrapper) fall back to
     * the generic depth-first walk, which finds their single next-page token.
     */
    private fun continuationToken(root: JSONObject): String? {
        findPlaylistListToken(root)?.let { return it }
        return walkForToken(root)
    }

    /** A continuation token inside the playlist's own video list (if any). */
    private fun findPlaylistListToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("playlistVideoListRenderer")?.let { list ->
                    // `nextContinuationData` (modern pages).
                    list.optJSONArray("continuations")?.let { conts ->
                        for (i in 0 until conts.length()) {
                            conts.optJSONObject(i)
                                ?.optJSONObject("nextContinuationData")
                                ?.optString("continuation", "")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { return it }
                        }
                    }
                    // A trailing "load more" continuation item (older pages).
                    list.optJSONArray("contents")?.let { contents ->
                        for (i in 0 until contents.length()) {
                            contents.optJSONObject(i)
                                ?.optJSONObject("continuationItemRenderer")
                                ?.optJSONObject("continuationEndpoint")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token", "")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { return it }
                        }
                    }
                }
                for (key in node.keys()) {
                    node.opt(key)?.let { child ->
                        findPlaylistListToken(child)?.let { return it }
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findPlaylistListToken(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * The continuation token inside a /next playlist panel: the trailing
     * `continuationItemRenderer` of the panel's contents array (that's where
     * the "load more" marker lives), falling back to a scoped depth-first
     * walk so unrelated tokens in the rest of the /next response (comments,
     * autoplay, end screens) can never be mistaken for the panel's own.
     */
    private fun panelContinuationToken(panel: JSONObject): String? {
        panel.optJSONArray("contents")?.let { contents ->
            for (i in 0 until contents.length()) {
                contents.optJSONObject(i)
                    ?.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token", "")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
        return walkForToken(panel)
    }

    /** Depth-first, document-order walk for the first continuation token. */
    private fun walkForToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("nextContinuationData")
                    ?.optString("continuation", "")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                node.optJSONObject("continuationCommand")
                    ?.optString("token", "")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                for (key in node.keys()) {
                    node.opt(key)?.let { child ->
                        walkForToken(child)?.let { return it }
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkForToken(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    /** Returns the balanced `{...}` string starting at [braceStart], or null. */
    private fun extractBalancedObject(s: String, braceStart: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in braceStart until s.length) {
            val c = s[i]
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(braceStart, i + 1)
                }
            }
        }
        return null
    }

    private fun parseInitialData(root: JSONObject): PlaylistInfo {
        // ── Title ──
        // Classic pages: header → playlistHeaderRenderer → title. Since 2025+
        // the header renderer is gone; the title moved to
        // metadata.playlistMetadataRenderer.title (plain string) and the
        // sidebar's playlistSidebarPrimaryInfoRenderer.title (runs node).
        var title = ""
        val header = root.optJSONObject("header")
        header?.optJSONObject("playlistHeaderRenderer")
            ?.optJSONObject("title")
            ?.let { title = textOf(it) }
        if (title.isBlank()) {
            root.optJSONObject("metadata")
                ?.optJSONObject("playlistMetadataRenderer")
                ?.optString("title", "")
                ?.takeIf { it.isNotBlank() }
                ?.let { title = it }
        }
        if (title.isBlank()) {
            root.optJSONObject("sidebar")
                ?.optJSONObject("playlistSidebarRenderer")
                ?.optJSONArray("items")
                ?.let { items ->
                    for (i in 0 until items.length()) {
                        items.optJSONObject(i)
                            ?.optJSONObject("playlistSidebarPrimaryInfoRenderer")
                            ?.optJSONObject("title")
                            ?.let { title = textOf(it) }
                        if (title.isNotBlank()) break
                    }
                }
        }

        // ── Videos: walk the tree in document order, collecting every
        // playlistVideoRenderer entry (each is one playlist item). ──
        val entries = ArrayList<JSONObject>()
        collect(root, entries)
        val videos = entries.mapNotNull { parseVideo(it) }

        return PlaylistInfo(title = title, videos = videos)
    }

    /**
     * Depth-first walk; every object holding a playlist item renderer key is
     * collected. Both the classic `playlistVideoRenderer` (initial pages and
     * browse continuations) and the modern `playlistPanelVideoRenderer` (/next
     * continuations) are playlist items with the same fields, so both feed
     * the same [parseVideo].
     */
    private fun collect(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("playlistVideoRenderer")?.let { out.add(it) }
                node.optJSONObject("playlistPanelVideoRenderer")?.let { out.add(it) }
                for (key in node.keys()) {
                    val child = node.opt(key)
                    if (child != null) collect(child, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collect(node.opt(i), out)
            }
        }
    }

    /** Reads a YouTube text node: `{"simpleText": "x"}` or `{"runs": [{"text": "x"}, …]}`. */
    private fun textOf(node: JSONObject?): String {
        if (node == null) return ""
        node.optString("simpleText", "").takeIf { it.isNotBlank() }?.let { return it }
        node.optJSONArray("runs")?.let { runs ->
            if (runs.length() > 0) {
                return buildString {
                    for (i in 0 until runs.length()) {
                        append(runs.optJSONObject(i)?.optString("text", "") ?: "")
                    }
                }
            }
        }
        return ""
    }

    private fun parseVideo(entry: JSONObject): MediaVideo? {
        val videoId = entry.optString("videoId", "")
        if (videoId.isBlank()) return null // unavailable / private entries have no id
        val title = textOf(entry.optJSONObject("title"))
        // Channel name + id (id best-effort from the byline's browse endpoint).
        var channelName = textOf(entry.optJSONObject("shortBylineText"))
            .ifBlank { textOf(entry.optJSONObject("longBylineText")) }
        var channelId = ""
        entry.optJSONObject("longBylineText")?.optJSONArray("runs")?.let { runs ->
            for (i in 0 until runs.length()) {
                val r = runs.optJSONObject(i) ?: continue
                r.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId", "")
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        channelId = it
                        if (channelName.isBlank()) channelName = r.optString("text", "")
                        return@let
                    }
            }
        }
        // Views: videoInfo runs often carry "1,234,567 views".
        var viewCount = 0L
        entry.optJSONObject("videoInfo")?.optJSONArray("runs")?.let { runs ->
            for (i in 0 until runs.length()) {
                val text = runs.optJSONObject(i)?.optString("text", "") ?: continue
                Regex("""([\d,]+)\s*views?""", RegexOption.IGNORE_CASE)
                    .find(text)?.let { m ->
                        viewCount = m.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                        return@let
                    }
            }
        }
        // Published: relative label ("3 days ago") converted to an epoch guess.
        val publishedText = textOf(entry.optJSONObject("publishedTimeText"))
        val publishedAt = parseRelativeTimeAgo(publishedText)
        val duration = parseClock(textOf(entry.optJSONObject("lengthText")))

        return MediaVideo(
            videoId = videoId,
            title = title,
            channelId = channelId,
            channelName = channelName,
            publishedAtEpochMillis = publishedAt,
            // Derived from the id — always available, always correct.
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            viewCount = viewCount,
            isShort = MediaVideo.isShortsTitle(title),
            isLive = false,
            durationSeconds = duration
        )
    }

    /** "12:34" / "1:02:03" → seconds; 0 when unparseable. */
    internal fun parseClock(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        // Strip a leading "LIVE"/"WATCH LIVE" tag if any.
        val cleaned = t.replace(Regex("""(?i)^(live|watch live)\s*"""), "")
        val parts = cleaned.split(":").mapNotNull { it.trim().toLongOrNull() }
        if (parts.isEmpty()) return 0L
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    /** Best-effort conversion of a YouTube relative label ("3 days ago") to epoch millis. */
    internal fun parseRelativeTimeAgo(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        val m = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)s?\s*ago""", RegexOption.IGNORE_CASE)
            .find(t)
            ?: return 0L
        val n = m.groupValues[1].toLongOrNull() ?: return 0L
        val unit = m.groupValues[2].lowercase(Locale.ROOT)
        val millis = when (unit) {
            "second" -> n * 1_000L
            "minute" -> n * 60_000L
            "hour" -> n * 3_600_000L
            "day" -> n * 86_400_000L
            "week" -> n * 604_800_000L
            "month" -> n * 2_592_000_000L
            "year" -> n * 31_536_000_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - millis
    }
}
