package com.muddassir.clearview.media.model

/**
 * A single YouTube video from a channel's RSS feed.
 *
 * @property videoId        YouTube video id (the "v=" parameter).
 * @property title          Video title.
 * @property channelId      The channel that published it.
 * @property channelName    Display name of that channel.
 * @property publishedAtEpochMillis  When the video was published (epoch ms).
 * @property thumbnailUrl   Thumbnail URL (i.ytimg.com). May be empty on parse failure.
 * @property viewCount      View count from the feed's media:statistics element
 *                          (0 when the feed omits it or the cache predates it).
 * @property isShort        True when this upload is a Short. Set by the parser
 *                          from the `#shorts` hashtag OR the channel's /shorts
 *                          tab; persisted in the cache (old caches default to
 *                          false until the next refresh).
 * @property isLive         True when this upload is a LIVE broadcast (the RSS
 *                          feed has no duration, so the parser keys off the
 *                          live thumbnail `…/hqdefault_live.jpg`). Live
 *                          broadcasts are never Shorts and are excluded from
 *                          watch-progress tracking (a live stream has no
 *                          finite duration to mark watched).
 * @property durationSeconds The video's length in seconds, or 0 when unknown.
 *                          The RSS feed omits durations, so this is resolved
 *                          per-video from the watch page (see
 *                          VideoDurationResolver) for the newest uploads and
 *                          persisted in the cache; used for the time badge on
 *                          feed cards.
 * @property isOfflineAudio True when this entry represents the DOWNLOADED
 *                          AUDIO of the video (added to a user playlist via
 *                          "Add audio to playlist…"), not the video itself.
 *                          Such an entry keeps the same [videoId], so a
 *                          playlist can hold BOTH the video and its offline
 *                          audio side by side; tapping one opens the player,
 *                          tapping the other plays the local audio file.
 *                          Persisted only in user-playlist JSON — feed videos
 *                          are always false (default).
 */
data class MediaVideo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val publishedAtEpochMillis: Long,
    val thumbnailUrl: String,
    val viewCount: Long = 0L,
    val isShort: Boolean = false,
    val isLive: Boolean = false,
    val durationSeconds: Long = 0L,
    val isOfflineAudio: Boolean = false,
    val platform: MediaPlatform = MediaPlatform.YOUTUBE,
    val instagramType: InstagramMediaType? = null,
    val mediaUrl: String? = null,
    val instagramUrl: String? = null
) {
    companion object {
        /**
         * Title-only Short signal: the #shorts hashtag that YouTube appends to
         * Shorts uploads. Word-bounded so titles like "#shortsy" or
         * "#shortsfortruth" don't false-positive, and "#short" alone (a common
         * keyword in long-video titles) never matches. Plain string logic
         * (deliberately NO regex — a raw-string word boundary can be mangled by
         * escaping layers when files are written programmatically).
         */
        fun isShortsTitle(title: String): Boolean {
            val tag = "#shorts"
            var i = title.indexOf(tag, ignoreCase = true)
            while (i >= 0) {
                val after = i + tag.length
                // A real #shorts tag sits at the end of the title or is followed
                // by a non-word character (space, punctuation, end).
                if (after >= title.length ||
                    !(title[after].isLetterOrDigit() || title[after] == '_')
                ) {
                    return true
                }
                i = title.indexOf(tag, i + 1, ignoreCase = true)
            }
            return false
        }
    }
}
