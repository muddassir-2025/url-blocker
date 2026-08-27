package com.muddassir.clearview.media.model

import java.util.Calendar
import java.util.TimeZone

/** Date presets for the All Feed filter (CUSTOM uses the picked range). */
enum class FeedDateFilter(val label: String) {
    ALL_TIME("All time"),
    TODAY("Today"),
    LAST_3_DAYS("Last 3 days"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    CUSTOM("Custom date range")
}

/**
 * Content-type filter for the All Feed.
 *
 * [LIVE] shows live broadcasts (the feed marks them via the live thumbnail;
 * the Live tab remains the dedicated live viewer). [DOWNLOADS] switches the
 * feed body to the offline Downloads section — the filter is handled by the
 * Media tab, and [com.muddassir.clearview.media.util.applyFeedFilter] treats
 * it as matching nothing (the feed list is bypassed entirely).
 */
enum class FeedContentFilter(val label: String) {
    ALL("All"),
    VIDEOS("Videos"),
    SHORTS("Shorts"),
    LIVE("Live"),
    DOWNLOADS("Downloads"),
    REELS("Reels"),
    IMAGE_POSTS("Image Posts")
}
enum class FeedPlatformFilter(val label: String) { ALL("All"), YOUTUBE("YouTube"), INSTAGRAM("Instagram") }

/** Sort order for the All Feed. */
enum class FeedSortOrder(val label: String) {
    NEWEST_FIRST("Newest first"),
    OLDEST_FIRST("Oldest first")
}

/**
 * Source filter for the All Feed — where each video came from:
 * [BY_URL] videos the user added manually by URL, and [SYSTEM] videos the
 * app pulled automatically from saved channels (RSS). [BY_RSS] is the
 * playlist-only option for exactly those channel-feed videos (inside user
 * playlists [SYSTEM] is presented as "From device" — device audio imported
 * into the playlist).
 */
enum class FeedSourceFilter(val label: String) {
    ALL("All"),
    BY_URL("By URL"),
    BY_RSS("By RSS"),
    SYSTEM("From channels")
}

/**
 * Media-type filter used inside user playlists — playlists can hold both
 * YouTube videos and audio imported from the device: [VIDEO] keeps only
 * YouTube videos, [AUDIO] keeps only the imported device audio.
 */
enum class PlaylistTypeFilter(val label: String) {
    ALL("All"),
    VIDEO("Video"),
    AUDIO("Audio")
}

/** Watch-status filter for the All Feed (combinable with the date filter). */
enum class FeedWatchStatus(val label: String) {
    ALL("All"),
    WATCHED("Watched"),
    UNWATCHED("Unwatched"),
    PARTIALLY_WATCHED("Partially watched")
}

/**
 * The Media tab's All Feed filters. Defaults: Last 3 days + All content +
 * Newest first. [customStartEpochMillis] / [customEndEpochMillis] only apply
 * when [date] is [FeedDateFilter.CUSTOM] (both are local start-of-day millis).
 *
 * The former Library filter (bookmarks) was removed — user playlists replaced
 * it. A persisted "library" key from an older build is simply ignored on
 * decode, so no stale filter can lock the feed.
 */
data class FeedFilter(
    val date: FeedDateFilter = FeedDateFilter.LAST_3_DAYS,
    val platform: FeedPlatformFilter = FeedPlatformFilter.ALL,
    val content: FeedContentFilter = FeedContentFilter.ALL,
    val sort: FeedSortOrder = FeedSortOrder.NEWEST_FIRST,
    // Unwatched by default: the feed leads with videos the user hasn't seen,
    // rather than everything mixed together.
    val watchStatus: FeedWatchStatus = FeedWatchStatus.UNWATCHED,
    /** Where the videos come from (manual by-URL, channels, playlists). */
    val source: FeedSourceFilter = FeedSourceFilter.ALL,
    /** Media type — only meaningful inside user playlists (YouTube video vs
     *  audio imported from the device). */
    val playlistType: PlaylistTypeFilter = PlaylistTypeFilter.ALL,
    val customStartEpochMillis: Long? = null,
    val customEndEpochMillis: Long? = null
) {
    /** True when anything differs from the defaults (drives the active indicator). */
    val isActive: Boolean
        get() = date != FeedDateFilter.LAST_3_DAYS ||
            platform != FeedPlatformFilter.ALL ||
            content != FeedContentFilter.ALL ||
            sort != FeedSortOrder.NEWEST_FIRST ||
            watchStatus != FeedWatchStatus.UNWATCHED ||
            source != FeedSourceFilter.ALL ||
            playlistType != PlaylistTypeFilter.ALL
}

/** Local start-of-day (midnight) for [epochMillis] in the device's time zone. */
fun startOfDayEpochMillis(epochMillis: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = epochMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/**
 * Converts the UTC-midnight millis returned by Material's [androidx.compose.material3.DatePicker]
 * into the LOCAL start-of-day millis (the picker reports the selected date at
 * 00:00 UTC; the device's UTC offset is added so the range covers the full
 * local day).
 */
fun datePickerMillisToLocalStart(utcMidnightMillis: Long): Long {
    val offset = TimeZone.getDefault().getOffset(utcMidnightMillis)
    return startOfDayEpochMillis(utcMidnightMillis + offset)
}
