package com.muddassir.clearview.media.model

/**
 * A saved YouTube playlist, imported by URL — the playlist analogue of a
 * saved channel. Its videos are fetched from the playlist page and cached
 * per playlist, then shown in the Media tab feed like any normal feed.
 *
 * @property playlistId  YouTube playlist id (the `list=` parameter).
 * @property title       Display title scraped from the playlist page.
 * @property sourceRef   The raw URL / id the user pasted (kept for reference).
 */
data class SavedPlaylist(
    val playlistId: String,
    val title: String,
    val sourceRef: String
)
