package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.FeedContentFilter
import com.muddassir.clearview.media.model.FeedDateFilter
import com.muddassir.clearview.media.model.FeedFilter
import com.muddassir.clearview.media.model.FeedSortOrder
import com.muddassir.clearview.media.model.FeedSourceFilter
import com.muddassir.clearview.media.model.FeedWatchStatus
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.PlaylistTypeFilter
import com.muddassir.clearview.media.model.startOfDayEpochMillis
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * The fraction at/above which a video counts as fully watched.
 * Must match [com.muddassir.clearview.media.data.WatchProgressStore.WATCHED_THRESHOLD].
 */
const val WATCHED_FRACTION_THRESHOLD = 0.9f

/** The minimum fraction for a video to count as "meaningfully started". */
const val STARTED_FRACTION_THRESHOLD = 0.02f

/**
 * Applies [filter] to [videos] purely locally (never refetches — only the
 * already-loaded videos are filtered), using [now] as the reference time for
 * the relative date presets. Filtering uses each video's real publication
 * timestamp. [progressOf] reports the watched fraction (0..1, null = never
 * started) — needed for the watch-status filter. [isManual] tells whether a
 * video was added by URL — needed for the source filter.
 */
fun applyFeedFilter(
    videos: List<MediaVideo>,
    filter: FeedFilter,
    now: Long = System.currentTimeMillis(),
    progressOf: (String) -> Float? = { null },
    isManual: (String) -> Boolean = { false }
): List<MediaVideo> {
    val start = when (filter.date) {
        FeedDateFilter.ALL_TIME -> null
        FeedDateFilter.TODAY -> startOfDayEpochMillis(now)
        FeedDateFilter.LAST_3_DAYS -> now - DAY_MS * 3
        FeedDateFilter.LAST_7_DAYS -> now - DAY_MS * 7
        FeedDateFilter.LAST_30_DAYS -> now - DAY_MS * 30
        FeedDateFilter.CUSTOM -> filter.customStartEpochMillis
    }
    val end = if (filter.date == FeedDateFilter.CUSTOM) filter.customEndEpochMillis else null

    val filtered = videos.filter { v ->
        val afterStart = start == null || v.publishedAtEpochMillis >= start
        val beforeEnd = end == null || v.publishedAtEpochMillis <= end
        val typeOk = when (filter.content) {
            FeedContentFilter.ALL -> true
            FeedContentFilter.VIDEOS -> !v.isShort
            FeedContentFilter.SHORTS -> v.isShort
            FeedContentFilter.LIVE -> v.isLive
            FeedContentFilter.INSTAGRAM_REELS -> v.mediaType == MediaVideo.MediaType.INSTAGRAM_REEL
            FeedContentFilter.INSTAGRAM_IMAGES -> v.mediaType == MediaVideo.MediaType.INSTAGRAM_IMAGE
            // The Downloads filter switches the Media tab to the dedicated
            // Downloads section; the feed list itself never shows here.
            FeedContentFilter.DOWNLOADS -> false
        }
        val p = progressOf(v.videoId)
        val statusOk = when (filter.watchStatus) {
            FeedWatchStatus.ALL -> true
            FeedWatchStatus.WATCHED -> (p ?: 0f) >= WATCHED_FRACTION_THRESHOLD
            FeedWatchStatus.UNWATCHED -> p == null || p < STARTED_FRACTION_THRESHOLD
            FeedWatchStatus.PARTIALLY_WATCHED ->
                p != null && p >= STARTED_FRACTION_THRESHOLD && p < WATCHED_FRACTION_THRESHOLD
        }
        val sourceOk = when (filter.source) {
            FeedSourceFilter.ALL -> true
            FeedSourceFilter.BY_URL -> isManual(v.videoId)
            // "From channels": pulled automatically from the saved channels.
            FeedSourceFilter.SYSTEM -> !isManual(v.videoId)
            // "By RSS" is offered inside user playlists; in the All Feed it
            // matches the same channel-feed videos as "From channels".
            FeedSourceFilter.BY_RSS -> !isManual(v.videoId)
        }
        afterStart && beforeEnd && typeOk && statusOk && sourceOk
    }

    return when (filter.sort) {
        FeedSortOrder.NEWEST_FIRST -> filtered.sortedByDescending { it.publishedAtEpochMillis }
        FeedSortOrder.OLDEST_FIRST -> filtered.sortedBy { it.publishedAtEpochMillis }
    }
}

/**
 * Short summary line for the filtered feed, e.g. "Last 7 days · 24 videos"
 * (adds the content type / watch status / library when they aren't "All").
 * Shown under the All Feed heading whenever a filter is active.
 */
fun feedFilterSummary(
    filter: FeedFilter,
    resultCount: Int
): String {
    val datePart = when (filter.date) {
        FeedDateFilter.ALL_TIME -> "All time"
        FeedDateFilter.TODAY -> "Today"
        FeedDateFilter.LAST_3_DAYS -> "Last 3 days"
        FeedDateFilter.LAST_7_DAYS -> "Last 7 days"
        FeedDateFilter.LAST_30_DAYS -> "Last 30 days"
        FeedDateFilter.CUSTOM -> customRangeLabel(filter)
    }
    val typePart = if (filter.content == FeedContentFilter.ALL) null else filter.content.label
    val statusPart =
        if (filter.watchStatus == FeedWatchStatus.ALL) null else filter.watchStatus.label
    val sourcePart = if (filter.source == FeedSourceFilter.ALL) null else filter.source.label
    return buildString {
        append(datePart)
        typePart?.let { append(" · ").append(it) }
        statusPart?.let { append(" · ").append(it) }
        sourcePart?.let { append(" · ").append(it) }
        append(" · ").append(resultCount).append(if (resultCount == 1) " video" else " videos")
    }
}

/** Compact range label for a custom filter, e.g. "12 Jan – 18 Jan 2026". */
private fun customRangeLabel(filter: FeedFilter): String {
    val start = filter.customStartEpochMillis ?: return "Custom"
    val end = filter.customEndEpochMillis ?: return "Custom"
    val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return "${fmt.format(Date(start))} – ${fmt.format(Date(end))}"
}

/** JSON-encodes [filter] so it can be persisted across app restarts. */
fun encodeFeedFilter(filter: FeedFilter): String =
    JSONObject()
        .put("date", filter.date.name)
        .put("content", filter.content.name)
        .put("sort", filter.sort.name)
        .put("watchStatus", filter.watchStatus.name)
        .put("source", filter.source.name)
        .put("playlistType", filter.playlistType.name)
        .put("customStart", filter.customStartEpochMillis ?: JSONObject.NULL)
        .put("customEnd", filter.customEndEpochMillis ?: JSONObject.NULL)
        .toString()

/**
 * Decodes a persisted filter; null when the value is missing or corrupt, so
 * callers can fall back to the default [FeedFilter]. Older persisted values
 * without the watch-status key decode to its default, the removed "library"
 * (bookmarks) key is ignored entirely, and the old "All time" date baseline
 * (the previous default) is migrated to the current "Last 3 days" default.
 */
fun decodeFeedFilter(json: String?): FeedFilter? {
    if (json.isNullOrBlank()) return null
    return try {
        val o = JSONObject(json)
        val date = runCatching { FeedDateFilter.valueOf(o.optString("date", "")) }.getOrNull()
            ?: return null
        var content = runCatching { FeedContentFilter.valueOf(o.optString("content", "")) }.getOrNull()
            ?: return null
        // The Live content filter was removed from the UI (Filter → Content); a
        // filter saved by an older build could still hold it, which would leave
        // the user with an active filter they can no longer reach. Migrate it to
        // All so the feed never silently locks to a hidden filter.
        if (content == FeedContentFilter.LIVE) content = FeedContentFilter.ALL
        // NOTE: the persisted date is deliberately NOT migrated. "All time" is
        // still a first-class option in the filter sheet, so a stored ALL_TIME
        // value is just as likely to be the user's explicit choice as an old
        // default — rewriting it to Last 3 days would silently discard their
        // selection on every restart. Fresh installs get the new Last 3 days
        // default through [FeedFilter]'s constructor default instead.
        val sort = runCatching { FeedSortOrder.valueOf(o.optString("sort", "")) }.getOrNull()
            ?: return null
        val watchStatus = runCatching {
            FeedWatchStatus.valueOf(o.optString("watchStatus", ""))
        }.getOrNull() ?: FeedWatchStatus.UNWATCHED
        // The source filter is new — values saved by older builds (no key, or
        // an unknown value) decode to its All default.
        val source = runCatching {
            FeedSourceFilter.valueOf(o.optString("source", ""))
        }.getOrNull() ?: FeedSourceFilter.ALL
        // The playlist media-type filter is new — unknown/missing values decode
        // to its All default (it's only meaningful inside user playlists).
        val playlistType = runCatching {
            PlaylistTypeFilter.valueOf(o.optString("playlistType", ""))
        }.getOrNull() ?: PlaylistTypeFilter.ALL
        FeedFilter(
            date = date,
            content = content,
            sort = sort,
            watchStatus = watchStatus,
            source = source,
            playlistType = playlistType,
            customStartEpochMillis = if (o.isNull("customStart")) null else o.optLong("customStart", 0L),
            customEndEpochMillis = if (o.isNull("customEnd")) null else o.optLong("customEnd", 0L)
        )
    } catch (e: Exception) {
        null
    }
}
