package com.muddassir.clearview.media.data

import android.content.Context

/**
 * Snapshot of a video's watch state: the watched fraction (0..1, the
 * long-standing card indicator) plus the real resume position and total
 * duration in seconds (used by Continue Watching).
 */
data class VideoProgress(
    val fraction: Float,
    val positionSeconds: Long,
    val durationSeconds: Long
)

/**
 * Persists how much of each video the user has watched (a fraction 0..1), so
 * the Media tab can render a progress bar on the thumbnail and a "Watched"
 * badge for completed videos.
 *
 * Keyed by the YouTube video id in a single SharedPreferences file; values are
 * plain floats (fraction of the total duration) plus the resume position and
 * duration (seconds). Cheap synchronous reads are fine — the Media tab reads
 * one value per card per composition.
 *
 * Written by [com.muddassir.clearview.media.ui.VideoPlayerScreen] from the
 * player's real `getCurrentTime()/getDuration()` reports (every ~5 s while
 * playing, plus once on pause/end).
 */
class WatchProgressStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored fraction (0..1) for [videoId], or null if never watched. */
    fun get(videoId: String): Float? =
        if (prefs.contains(videoId)) prefs.getFloat(videoId, -1f).takeIf { it >= 0f } else null

    /** Persists [fraction] (clamped to 0..1). Negative values are ignored. */
    fun set(videoId: String, fraction: Float) {
        if (fraction < 0f) return
        prefs.edit().putFloat(videoId, fraction.coerceIn(0f, 1f)).apply()
    }

    /** The full watch snapshot for [videoId], or null if never watched. */
    fun getProgress(videoId: String): VideoProgress? {
        val fraction = get(videoId) ?: return null
        return VideoProgress(
            fraction = fraction,
            positionSeconds = prefs.getLong(POS_PREFIX + videoId, 0L),
            durationSeconds = prefs.getLong(DUR_PREFIX + videoId, 0L)
        )
    }

    /** Persists fraction + resume position + duration in one write. */
    fun setProgress(
        videoId: String,
        fraction: Float,
        positionSeconds: Long,
        durationSeconds: Long
    ) {
        if (fraction < 0f) return
        prefs.edit()
            .putFloat(videoId, fraction.coerceIn(0f, 1f))
            .putLong(POS_PREFIX + videoId, positionSeconds.coerceAtLeast(0L))
            .putLong(DUR_PREFIX + videoId, durationSeconds.coerceAtLeast(0L))
            .apply()
    }

    /** True once ~90% or more of the video was watched. */
    fun isWatched(videoId: String): Boolean = (get(videoId) ?: 0f) >= WATCHED_THRESHOLD

    fun remove(videoId: String) {
        prefs.edit()
            .remove(videoId)
            .remove(POS_PREFIX + videoId)
            .remove(DUR_PREFIX + videoId)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "media_watch_progress"
        const val WATCHED_THRESHOLD = 0.9f
        const val POS_PREFIX = "pos_"
        const val DUR_PREFIX = "dur_"
    }
}
