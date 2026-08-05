package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.MediaChannelUpdate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure helpers for the "Latest Updates" feed (home tab) — the persisted list
 * of channel updates detected by the background worker (each entry matches a
 * notification the user received). Kept free of Android dependencies so the
 * merge/cap/dismiss logic is unit-testable on the JVM.
 */
object MediaUpdates {

    /** Max entries kept in the feed — the user-visible "latest 10 updates". */
    const val MAX = 10

    /**
     * Merges [incoming] updates into [existing] history: deduped by video id
     * (a video is only ever listed once), newest first, capped at [max].
     */
    fun merge(
        existing: List<MediaChannelUpdate>,
        incoming: List<MediaChannelUpdate>,
        max: Int = MAX
    ): List<MediaChannelUpdate> =
        (incoming + existing)
            .distinctBy { it.latestVideoId }
            .sortedByDescending { it.publishedAtEpochMillis }
            .take(max)

    /** JSON-encodes the list for SharedPreferences persistence. */
    fun encode(updates: List<MediaChannelUpdate>): String {
        val arr = JSONArray()
        updates.forEach { u ->
            arr.put(
                JSONObject()
                    .put("channelId", u.channelId)
                    .put("channelName", u.channelName)
                    .put("latestVideoId", u.latestVideoId)
                    .put("latestVideoTitle", u.latestVideoTitle)
                    .put("publishedAt", u.publishedAtEpochMillis)
            )
        }
        return arr.toString()
    }

    /** Decodes the persisted JSON; empty list on blank/corrupt input. */
    fun decode(json: String?): List<MediaChannelUpdate> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val videoId = o.optString("latestVideoId", "")
                if (videoId.isBlank()) {
                    null
                } else {
                    MediaChannelUpdate(
                        channelId = o.optString("channelId", ""),
                        channelName = o.optString("channelName", ""),
                        latestVideoId = videoId,
                        latestVideoTitle = o.optString("latestVideoTitle", ""),
                        publishedAtEpochMillis = o.optLong("publishedAt", 0L)
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
