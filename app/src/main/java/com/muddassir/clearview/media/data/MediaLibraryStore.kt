package com.muddassir.clearview.media.data

import android.content.Context
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.util.MediaVideos

/**
 * The user's media library: hidden videos and manually added videos,
 * persisted as full [MediaVideo] metadata lists in a single SharedPreferences
 * file so hidden / manual videos survive restarts — and manual videos stay
 * available even when they are no longer present in the latest RSS feed.
 *
 * Hidden videos also keep their metadata so the management UI can list them
 * for unhiding.
 *
 * The old bookmark feature was removed (user playlists replaced it); its
 * "bookmarks" prefs key is simply never read anymore.
 */
class MediaLibraryStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        const val KEY_HIDDEN = "hidden"
        const val KEY_MANUAL = "manual"
    }
}
