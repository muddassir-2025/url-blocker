package com.muddassir.clearview.media.model

/**
 * A user-saved media source (a YouTube channel).
 *
 * @property channelId  Resolved YouTube channel id (UC…).
 * @property displayName  Human-friendly name shown in the UI.
 * @property sourceRef  What the user originally added (a @handle, a channel URL
 *                      or a bare channel id) — kept for display/round-trips.
 * @property avatarUrl  Channel avatar image URL (yt3.ggpht.com), scraped from
 *                      the channel page. Null when not fetched yet / failed —
 *                      the UI falls back to initials.
 */
data class SavedChannel(
    val channelId: String,
    val displayName: String,
    val sourceRef: String,
    val avatarUrl: String? = null
)
