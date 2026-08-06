package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.UserPlaylist
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure helpers for persisting and editing [UserPlaylist] lists. Kept free of
 * Android dependencies so every operation is unit-testable on the JVM.
 *
 * All mutations are functional: they take the current list and return the new
 * one, so the UI can store one source of truth and always re-render from it.
 */
object UserPlaylists {

    /** JSON-encodes the playlists for SharedPreferences persistence. */
    fun encode(playlists: List<UserPlaylist>): String {
        val arr = JSONArray()
        playlists.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("createdAt", p.createdAtEpochMillis)
                    .put("videos", MediaVideos.encode(p.videos))
            )
        }
        return arr.toString()
    }

    /** Decodes the persisted JSON; empty list on blank/corrupt input. */
    fun decode(json: String?): List<UserPlaylist> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id", "")
                    if (id.isBlank()) {
                        null
                    } else {
                        UserPlaylist(
                            id = id,
                            name = o.optString("name", "Playlist"),
                            createdAtEpochMillis = o.optLong("createdAt", 0L),
                            videos = MediaVideos.decode(o.optString("videos", ""))
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

    /** Renames the playlist with [id]; returns the list unchanged when absent. */
    fun withRenamed(playlists: List<UserPlaylist>, id: String, newName: String): List<UserPlaylist> {
        val name = newName.trim().ifBlank { return playlists }
        return playlists.map { if (it.id == id) it.copy(name = name) else it }
    }

    /** Removes the playlist with [id]. */
    fun withRemoved(playlists: List<UserPlaylist>, id: String): List<UserPlaylist> =
        playlists.filterNot { it.id == id }

    /**
     * Appends [videos] to the playlist with [id], deduped by video id (a video
     * already in the playlist is skipped — no duplicates can ever occur).
     */
    fun withVideosAdded(
        playlists: List<UserPlaylist>,
        id: String,
        videos: List<MediaVideo>
    ): List<UserPlaylist> {
        if (videos.isEmpty()) return playlists
        return playlists.map { p ->
            if (p.id != id) p
            else {
                val existing = p.videos.map { it.videoId }.toSet()
                val fresh = videos.filter { it.videoId !in existing }
                if (fresh.isEmpty()) p else p.copy(videos = p.videos + fresh)
            }
        }
    }

    /** Removes one video (by id) from the playlist with [id]. */
    fun withVideoRemoved(
        playlists: List<UserPlaylist>,
        id: String,
        videoId: String
    ): List<UserPlaylist> =
        playlists.map { p ->
            if (p.id != id) p
            else p.copy(videos = p.videos.filterNot { it.videoId == videoId })
        }

    /**
     * Moves the video at [fromIndex] to [toIndex] inside the playlist with
     * [id] (a no-op for out-of-range / identical indices). Used by the reorder
     * controls in the playlist editor.
     */
    fun withVideoMoved(
        playlists: List<UserPlaylist>,
        id: String,
        fromIndex: Int,
        toIndex: Int
    ): List<UserPlaylist> {
        if (fromIndex == toIndex) return playlists
        return playlists.map { p ->
            if (p.id != id) p
            else {
                val list = p.videos
                if (fromIndex !in list.indices || toIndex !in list.indices) return@map p
                val moved = list[fromIndex]
                val rest = list.toMutableList().apply { removeAt(fromIndex) }
                rest.add(toIndex, moved)
                p.copy(videos = rest)
            }
        }
    }
}
