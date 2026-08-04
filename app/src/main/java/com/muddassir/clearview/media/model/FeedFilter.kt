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

/** Content-type filter for the All Feed. */
enum class FeedContentFilter(val label: String) {
    ALL("All"),
    VIDEOS("Videos"),
    SHORTS("Shorts")
}

/** Sort order for the All Feed. */
enum class FeedSortOrder(val label: String) {
    NEWEST_FIRST("Newest first"),
    OLDEST_FIRST("Oldest first")
}

/** Watch-status filter for the All Feed (combinable with the date filter). */
enum class FeedWatchStatus(val label: String) {
    ALL("All"),
    WATCHED("Watched"),
    UNWATCHED("Unwatched"),
    PARTIALLY_WATCHED("Partially watched")
}

/** Library filter: every video vs bookmarked only. */
enum class FeedLibraryFilter(val label: String) {
    ALL("All"),
    BOOKMARKED("Bookmarked")
}

/**
 * The Media tab's All Feed filters. Defaults: All time + All content +
 * Newest first. [customStartEpochMillis] / [customEndEpochMillis] only apply
 * when [date] is [FeedDateFilter.CUSTOM] (both are local start-of-day millis).
 */
data class FeedFilter(
    val date: FeedDateFilter = FeedDateFilter.ALL_TIME,
    val content: FeedContentFilter = FeedContentFilter.ALL,
    val sort: FeedSortOrder = FeedSortOrder.NEWEST_FIRST,
    // Unwatched by default: the feed leads with videos the user hasn't seen,
    // rather than everything mixed together.
    val watchStatus: FeedWatchStatus = FeedWatchStatus.UNWATCHED,
    val library: FeedLibraryFilter = FeedLibraryFilter.ALL,
    val customStartEpochMillis: Long? = null,
    val customEndEpochMillis: Long? = null
) {
    /** True when anything differs from the defaults (drives the active indicator). */
    val isActive: Boolean
        get() = date != FeedDateFilter.ALL_TIME ||
            content != FeedContentFilter.ALL ||
            sort != FeedSortOrder.NEWEST_FIRST ||
            watchStatus != FeedWatchStatus.UNWATCHED ||
            library != FeedLibraryFilter.ALL
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
