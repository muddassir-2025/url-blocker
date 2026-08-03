package com.example.url_blocker.media.model

/**
 * A single YouTube video from a channel's RSS feed.
 *
 * @property videoId        YouTube video id (the "v=" parameter).
 * @property title          Video title.
 * @property channelId      The channel that published it.
 * @property channelName    Display name of that channel.
 * @property publishedAtEpochMillis  When the video was published (epoch ms).
 * @property thumbnailUrl   Thumbnail URL (i.ytimg.com). May be empty on parse failure.
 */
data class MediaVideo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val publishedAtEpochMillis: Long,
    val thumbnailUrl: String
)
