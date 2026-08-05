package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.FeedContentFilter
import com.muddassir.clearview.media.model.FeedDateFilter
import com.muddassir.clearview.media.model.FeedFilter
import com.muddassir.clearview.media.model.FeedLibraryFilter
import com.muddassir.clearview.media.model.FeedSortOrder
import com.muddassir.clearview.media.model.FeedWatchStatus
import com.muddassir.clearview.media.model.MediaVideo
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
 * started) and [bookmarkedOf] whether a video is bookmarked — both needed for
 * the watch-status and bookmarked filters.
 */
fun applyFeedFilter(
    videos: List<MediaVideo>,
    filter: FeedFilter,
    now: Long = System.currentTimeMillis(),
    progressOf: (String) -> Float? = { null },
    bookmarkedOf: (String) -> Boolean = { false }
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
        val libOk =
            filter.library == FeedLibraryFilter.ALL || bookmarkedOf(v.videoId)
        afterStart && beforeEnd && typeOk && statusOk && libOk
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
    val libPart = if (filter.library == FeedLibraryFilter.ALL) null else filter.library.label
    return buildString {
        append(datePart)
        typePart?.let { append(" · ").append(it) }
        statusPart?.let { append(" · ").append(it) }
        libPart?.let { append(" · ").append(it) }
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
        .put("library", filter.library.name)
        .put("customStart", filter.customStartEpochMillis ?: JSONObject.NULL)
        .put("customEnd", filter.customEndEpochMillis ?: JSONObject.NULL)
        .toString()

/**
 * Decodes a persisted filter; null when the value is missing or corrupt, so
 * callers can fall back to the default [FeedFilter]. Older persisted values
 * without the watch-status / library keys decode to their defaults.
 */
fun decodeFeedFilter(json: String?): FeedFilter? {
    if (json.isNullOrBlank()) return null
    return try {
        val o = JSONObject(json)
        val date = runCatching { FeedDateFilter.valueOf(o.optString("date", "")) }.getOrNull()
            ?: return null
        val content = runCatching { FeedContentFilter.valueOf(o.optString("content", "")) }.getOrNull()
            ?: return null
        val sort = runCatching { FeedSortOrder.valueOf(o.optString("sort", "")) }.getOrNull()
            ?: return null
        val watchStatus = runCatching {
            FeedWatchStatus.valueOf(o.optString("watchStatus", ""))
        }.getOrNull() ?: FeedWatchStatus.UNWATCHED
        val library = runCatching {
            FeedLibraryFilter.valueOf(o.optString("library", ""))
        }.getOrNull() ?: FeedLibraryFilter.ALL
        FeedFilter(
            date = date,
            content = content,
            sort = sort,
            watchStatus = watchStatus,
            library = library,
            customStartEpochMillis = if (o.isNull("customStart")) null else o.optLong("customStart", 0L),
            customEndEpochMillis = if (o.isNull("customEnd")) null else o.optLong("customEnd", 0L)
        )
    } catch (e: Exception) {
        null
    }
}
