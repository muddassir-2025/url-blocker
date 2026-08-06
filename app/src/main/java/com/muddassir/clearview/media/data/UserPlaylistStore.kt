package com.muddassir.clearview.media.data

import android.content.Context
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.UserPlaylist
import com.muddassir.clearview.media.util.UserPlaylists
import java.util.UUID

/**
 * Local persistence for user-created playlists. Everything is stored as JSON
 * in a single SharedPreferences file (videos keep their full metadata, so a
 * playlist renders even when the videos left the RSS feed — same trade as the
 * bookmarks store). All edits are functional: the store returns the updated
 * list so callers can refresh their UI state atomically.
 */
class UserPlaylistStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Every saved playlist, creation order. */
    fun getPlaylists(): List<UserPlaylist> =
        UserPlaylists.decode(prefs.getString(KEY_PLAYLISTS, null))

    /** Creates a playlist with [name] (optionally seeded with [videos]); returns it. */
    fun createPlaylist(name: String, videos: List<MediaVideo> = emptyList()): UserPlaylist {
        val playlist = UserPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "My playlist" },
            videos = videos,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        save(getPlaylists() + playlist)
        return playlist
    }

    /** Renames the playlist with [id]; no-op when blank or absent. */
    fun renamePlaylist(id: String, newName: String) {
        save(UserPlaylists.withRenamed(getPlaylists(), id, newName))
    }

    /** Deletes the playlist with [id] permanently. */
    fun deletePlaylist(id: String) {
        save(UserPlaylists.withRemoved(getPlaylists(), id))
    }

    /** Appends [videos] to the playlist with [id] (deduped by video id). */
    fun addVideos(id: String, videos: List<MediaVideo>) {
        save(UserPlaylists.withVideosAdded(getPlaylists(), id, videos))
    }

    /** Removes one video from the playlist with [id]. */
    fun removeVideo(id: String, videoId: String) {
        save(UserPlaylists.withVideoRemoved(getPlaylists(), id, videoId))
    }

    /** Moves the video at [fromIndex] to [toIndex] within the playlist with [id]. */
    fun moveVideo(id: String, fromIndex: Int, toIndex: Int) {
        save(UserPlaylists.withVideoMoved(getPlaylists(), id, fromIndex, toIndex))
    }

    private fun save(playlists: List<UserPlaylist>) {
        prefs.edit().putString(KEY_PLAYLISTS, UserPlaylists.encode(playlists)).apply()
    }

    private companion object {
        const val PREFS_NAME = "user_playlists"
        const val KEY_PLAYLISTS = "playlists"
    }
}
