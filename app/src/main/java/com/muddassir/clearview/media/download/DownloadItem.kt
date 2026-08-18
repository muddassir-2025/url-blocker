package com.muddassir.clearview.media.download

import org.json.JSONArray
import org.json.JSONObject

/**
 * One offline audio download, persisted in `cache/audio/metadata.json`.
 *
 * The audio file lives in `cache/audio/` as [fileName] (e.g. "abc123.m4a"); the
 * local thumbnail at `cache/thumbnails/[thumbnailPath]`. Names (not absolute
 * paths) are stored so metadata stays valid even if the cache directory moves
 * between app updates — the actual files are resolved against the current
 * cache dir via [AudioDownloadStore].
 *
 * [source] is [SOURCE_RSS] (from a saved channel's feed) or [SOURCE_URL]
 * (manually added by URL). [expiresAt] is always 0 — downloads are never
 * auto-deleted, so the field exists only for JSON compatibility with metadata
 * written by older builds that had the (removed) 15-day expiry rule.
 */
data class DownloadItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val source: String,
    val fileName: String,
    val fileSize: Long,
    val downloadedAt: Long,
    val lastPlayed: Long = 0L,
    val expiresAt: Long = 0L,
    val thumbnailPath: String = "",
    val durationSeconds: Long = 0L,
    /** The video's channel id (empty for downloads made before this field
     *  existed, or when the channel id wasn't known). Drives per-channel
     *  filtering in the Downloads view. */
    val channelId: String = ""
) {
    companion object {
        const val SOURCE_RSS = "rss"
        const val SOURCE_URL = "url"
        /** Imported from the device's local storage (not downloaded). */
        const val SOURCE_DEVICE = "device"
    }
}

/**
 * JSON (de)serialization for [DownloadItem] lists — pure JVM (mirrors the
 * existing [com.muddassir.clearview.media.util.MediaVideos] pattern) so the
 * round-trip logic is unit-testable.
 */
object DownloadItems {

    fun encode(items: List<DownloadItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("videoId", item.videoId)
                    .put("title", item.title)
                    .put("channelName", item.channelName)
                    .put("source", item.source)
                    .put("fileName", item.fileName)
                    .put("fileSize", item.fileSize)
                    .put("downloadedAt", item.downloadedAt)
                    .put("lastPlayed", item.lastPlayed)
                    .put("expiresAt", item.expiresAt)
                    .put("thumbnailPath", item.thumbnailPath)
                    .put("durationSeconds", item.durationSeconds)
                    .put("channelId", item.channelId)
            )
        }
        return arr.toString()
    }

    /** Decodes the persisted JSON; empty list on blank/corrupt input. */
    fun decode(json: String?): List<DownloadItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    val videoId = o.optString("videoId", "")
                    if (videoId.isBlank()) return@mapNotNull null
                    DownloadItem(
                        videoId = videoId,
                        title = o.optString("title", ""),
                        channelName = o.optString("channelName", ""),
                        source = o.optString("source", DownloadItem.SOURCE_RSS),
                        fileName = o.optString("fileName", ""),
                        fileSize = o.optLong("fileSize", 0L),
                        downloadedAt = o.optLong("downloadedAt", 0L),
                        lastPlayed = o.optLong("lastPlayed", 0L),
                        expiresAt = o.optLong("expiresAt", 0L),
                        thumbnailPath = o.optString("thumbnailPath", ""),
                        durationSeconds = o.optLong("durationSeconds", 0L),
                        channelId = o.optString("channelId", "")
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
