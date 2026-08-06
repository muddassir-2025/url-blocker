package com.muddassir.clearview.media.model

/**
 * A user-created playlist — a curated, locally-saved list of videos in an
 * order the user controls. Unlike an imported YouTube playlist (whose order
 * comes from YouTube), the user can create, rename, delete, add to, remove
 * from, and reorder these freely.
 *
 * @property id       Stable unique id (UUID).
 * @property name     Display name.
 * @property videos   The videos in their current playlist order (full
 *                    metadata is persisted so playlists render offline, like
 *                    bookmarks do).
 * @property createdAtEpochMillis  When the playlist was created.
 */
data class UserPlaylist(
    val id: String,
    val name: String,
    val videos: List<MediaVideo>,
    val createdAtEpochMillis: Long
)
