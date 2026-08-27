package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.MediaVideo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure helpers for persisting [MediaVideo] lists — used by the library store
 * (hidden / manually added). Kept free of Android dependencies so
 * the round-trip logic is unit-testable on the JVM. Mirrors the per-channel
 * cache encoding in [com.muddassir.clearview.media.data.MediaRepository].
 */
object MediaVideos {

    /** JSON-encodes the list for SharedPreferences persistence. */
    fun encode(videos: List<MediaVideo>): String {
        val arr = JSONArray()
        videos.forEach { v ->
            arr.put(
                JSONObject()
                    .put("videoId", v.videoId)
                    .put("title", v.title)
                    .put("channelId", v.channelId)
                    .put("channelName", v.channelName)
                    .put("publishedAt", v.publishedAtEpochMillis)
                    .put("thumbnailUrl", v.thumbnailUrl)
                    .put("viewCount", v.viewCount)
                    .put("isShort", v.isShort)
                    .put("isLive", v.isLive)
                    .put("durationSeconds", v.durationSeconds)
                    .put("isOfflineAudio", v.isOfflineAudio)
                    .put("platform", v.platform.name)
                    .put("instagramType", v.instagramType?.name ?: JSONObject.NULL)
                    .put("mediaUrl", v.mediaUrl ?: JSONObject.NULL)
                    .put("instagramUrl", v.instagramUrl ?: JSONObject.NULL)
            )
        }
        return arr.toString()
    }

    /** Decodes the persisted JSON; empty list on blank/corrupt input. */
    fun decode(json: String?): List<MediaVideo> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    val videoId = o.optString("videoId", "")
                    if (videoId.isBlank()) {
                        null
                    } else {
                        MediaVideo(
                            videoId = videoId,
                            title = o.optString("title", ""),
                            channelId = o.optString("channelId", ""),
                            channelName = o.optString("channelName", ""),
                            publishedAtEpochMillis = o.optLong("publishedAt", 0L),
                            thumbnailUrl = o.optString("thumbnailUrl", ""),
                            viewCount = o.optLong("viewCount", 0L),
                            isShort = o.optBoolean("isShort", false),
                            isLive = o.optBoolean("isLive", false),
                            durationSeconds = o.optLong("durationSeconds", 0L),
                            isOfflineAudio = o.optBoolean("isOfflineAudio", false),
                            platform = runCatching { com.muddassir.clearview.media.model.MediaPlatform.valueOf(o.optString("platform","YOUTUBE")) }.getOrDefault(com.muddassir.clearview.media.model.MediaPlatform.YOUTUBE),
                            instagramType = o.optString("instagramType","").takeIf { it.isNotBlank() }?.let { runCatching { com.muddassir.clearview.media.model.InstagramMediaType.valueOf(it) }.getOrNull() },
                            mediaUrl = o.optString("mediaUrl", "").takeIf { it.isNotBlank() },
                            instagramUrl = o.optString("instagramUrl", "").takeIf { it.isNotBlank() }
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Merges two video lists by id, newest first. [authoritative] wins when the
     * same id appears in both (an RSS copy carries fresher metadata than a
     * stored manual copy).
     */
    fun merge(
        primary: List<MediaVideo>,
        supplemental: List<MediaVideo>
    ): List<MediaVideo> =
        (primary + supplemental)
            .distinctBy { it.videoId }
            .sortedByDescending { it.publishedAtEpochMillis }
}
