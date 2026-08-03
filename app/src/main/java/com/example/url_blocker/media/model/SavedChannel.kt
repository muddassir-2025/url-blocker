package com.example.url_blocker.media.model

/**
 * A user-saved media source (a YouTube channel).
 *
 * @property channelId  Resolved YouTube channel id (UC…).
 * @property displayName  Human-friendly name shown in the UI.
 * @property sourceRef  What the user originally added (a @handle, a channel URL
 *                      or a bare channel id) — kept for display/round-trips.
 */
data class SavedChannel(
    val channelId: String,
    val displayName: String,
    val sourceRef: String
)
