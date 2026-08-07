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
 * @property addedAtEpochMillis  When the user subscribed (epoch ms). The
 *                      notification worker only treats videos published at/after
 *                      this moment as potentially new — the guard that stops a
 *                      channel's pre-existing backlog from ever generating
 *                      notifications, even if the add-time baseline fetch
 *                      failed. 0 for channels added before this field existed
 *                      (guard inactive — their behavior is unchanged).
 */
data class SavedChannel(
    val channelId: String,
    val displayName: String,
    val sourceRef: String,
    val avatarUrl: String? = null,
    val addedAtEpochMillis: Long = 0L
)
