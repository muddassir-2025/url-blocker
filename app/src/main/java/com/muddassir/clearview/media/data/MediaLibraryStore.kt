package com.muddassir.clearview.media.data

import android.content.Context
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.util.MediaVideos

/**
 * The user's media library: bookmarks, hidden videos, and manually added
 * videos. All three are persisted as full [MediaVideo] metadata lists in a
 * single SharedPreferences file so bookmarked / hidden / manual videos survive
 * restarts — and bookmarked or manual videos stay available even when they are
 * no longer present in the latest RSS feed.
 *
 * Hidden videos also keep their metadata so the management UI can list them
 * for unhiding.
 */
class MediaLibraryStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Bookmarks ──────────────────────────────────────────────────

    fun isBookmarked(videoId: String): Boolean =
        getBookmarkedVideos().any { it.videoId == videoId }

    fun getBookmarkedVideos(): List<MediaVideo> =
        MediaVideos.decode(prefs.getString(KEY_BOOKMARKS, null))

    /** Toggles the bookmark for [video]; returns the NEW state (true = bookmarked). */
    fun toggleBookmark(video: MediaVideo): Boolean {
        val current = getBookmarkedVideos()
        return if (current.any { it.videoId == video.videoId }) {
            saveBookmarks(current.filterNot { it.videoId == video.videoId })
            false
        } else {
            saveBookmarks(listOf(video) + current)
            true
        }
    }

    private fun saveBookmarks(videos: List<MediaVideo>) {
        prefs.edit().putString(KEY_BOOKMARKS, MediaVideos.encode(videos)).apply()
    }

    // ── Hidden videos ──────────────────────────────────────────────

    fun isHidden(videoId: String): Boolean =
        getHiddenVideos().any { it.videoId == videoId }

    fun getHiddenVideos(): List<MediaVideo> =
        MediaVideos.decode(prefs.getString(KEY_HIDDEN, null))

    fun hideVideo(video: MediaVideo) {
        if (isHidden(video.videoId)) return
        prefs.edit()
            .putString(KEY_HIDDEN, MediaVideos.encode(getHiddenVideos() + video))
            .apply()
    }

    fun unhideVideo(videoId: String) {
        saveHidden(getHiddenVideos().filterNot { it.videoId == videoId })
    }

    fun unhideAll() {
        prefs.edit().remove(KEY_HIDDEN).apply()
    }

    private fun saveHidden(videos: List<MediaVideo>) {
        prefs.edit().putString(KEY_HIDDEN, MediaVideos.encode(videos)).apply()
    }

    // ── Manually added videos (added by URL, not from RSS) ─────────

    fun isManuallyAdded(videoId: String): Boolean =
        getManuallyAddedVideos().any { it.videoId == videoId }

    fun getManuallyAddedVideos(): List<MediaVideo> =
        MediaVideos.decode(prefs.getString(KEY_MANUAL, null))

    /** Adds [video]; returns false when it is already present (no duplicate). */
    fun addManuallyAdded(video: MediaVideo): Boolean {
        if (isManuallyAdded(video.videoId)) return false
        prefs.edit()
            .putString(KEY_MANUAL, MediaVideos.encode(getManuallyAddedVideos() + video))
            .apply()
        return true
    }

    fun removeManuallyAdded(videoId: String) {
        saveManual(getManuallyAddedVideos().filterNot { it.videoId == videoId })
    }

    private fun saveManual(videos: List<MediaVideo>) {
        prefs.edit().putString(KEY_MANUAL, MediaVideos.encode(videos)).apply()
    }

    private companion object {
        const val PREFS_NAME = "media_library"
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_HIDDEN = "hidden"
        const val KEY_MANUAL = "manual"
    }
}
