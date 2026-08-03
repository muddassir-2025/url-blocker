package com.example.url_blocker.media.model

/**
 * A channel that has a new video, surfaced on the home (Quran) tab's
 * "Latest Updates" feed and used for the media notification
 * ("Safina Society has an update").
 */
data class MediaChannelUpdate(
    val channelId: String,
    val channelName: String,
    val latestVideoId: String,
    val latestVideoTitle: String,
    val publishedAtEpochMillis: Long
)
