package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.MediaVideo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/**
 * Pure parser for a YouTube playlist page's embedded `ytInitialData` JSON.
 * No network, no Android dependencies — unit-testable on the JVM.
 *
 * The playlist page embeds the whole playlist state as JSON; this parser
 * walks it (recursively, in document order — which IS the playlist order) and
 * pulls the playlist title plus every `playlistVideoRenderer` entry. A
 * private / unavailable playlist has no video list at all, which the caller
 * (MediaRepository) turns into a friendly error.
 */
object PlaylistPageParser {

    /** Parsed playlist: title + videos in playlist order. */
    data class PlaylistInfo(
        val title: String,
        val videos: List<MediaVideo>
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
        val matcher = INITIAL_DATA.matcher(html)
        if (!matcher.find()) return null
        val json = matcher.group(1) ?: return null
        return try {
            parseInitialData(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseInitialData(root: JSONObject): PlaylistInfo {
        // ── Title: find the first playlistHeaderRenderer → title ──
        var title = ""
        val header = root.optJSONObject("header")
        header?.optJSONObject("playlistHeaderRenderer")
            ?.optJSONObject("title")
            ?.let { title = textOf(it) }

        // ── Videos: walk the tree in document order, collecting every
        // playlistVideoRenderer entry (each is one playlist item). ──
        val entries = ArrayList<JSONObject>()
        collect(root, entries)
        val videos = entries.mapNotNull { parseVideo(it) }

        return PlaylistInfo(title = title, videos = videos)
    }

    /** Depth-first walk; every object holding a `playlistVideoRenderer` key is collected. */
    private fun collect(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("playlistVideoRenderer")?.let { out.add(it) }
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
