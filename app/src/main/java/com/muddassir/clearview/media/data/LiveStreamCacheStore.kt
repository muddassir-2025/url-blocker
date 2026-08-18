package com.muddassir.clearview.media.data

import android.content.Context

/**
 * Persists each stream's LAST successfully resolved live video id, separately
 * per official channel, so the resolver's last-known fallback survives app
 * restarts and the user never needs an app update when a new broadcast starts.
 *
 * Values are keyed by the OFFICIAL CHANNEL ID — Makkah and Madinah can never
 * share an id. The id is only ever written from that channel's own successful
 * resolution, and the resolver ALWAYS re-validates the cached id (still live,
 * correct channel, playable) before playing it — never blindly.
 *
 * Follows the codebase's SharedPreferences store pattern (constructor Context,
 * `context.applicationContext.getSharedPreferences(...)`).
 */
class LiveStreamCacheStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Last successfully resolved live video id for [channelId], or null. */
    fun lastVideoId(channelId: String): String? =
        prefs.getString(KEY_VIDEO + channelId, null)?.takeIf { it.length == VIDEO_ID_LENGTH }

    /** Timestamp (epoch millis) of the last successful resolution, or 0. */
    fun lastResolvedAtMillis(channelId: String): Long =
        prefs.getLong(KEY_AT + channelId, 0L)

    /** Records a successful resolution for [channelId] (replaces any old id). */
    fun rememberResolved(channelId: String, videoId: String) {
        prefs.edit()
            .putString(KEY_VIDEO + channelId, videoId)
            .putLong(KEY_AT + channelId, System.currentTimeMillis())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "live_stream_cache"
        const val KEY_VIDEO = "last_video_"
        const val KEY_AT = "last_at_"
        const val VIDEO_ID_LENGTH = 11
    }
}
