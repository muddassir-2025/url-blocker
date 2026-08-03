package com.muddassir.clearview.media.model

/**
 * A live broadcast source (Makkah / Madinah).
 *
 * Played INSIDE the app with the same YouTube IFrame player as regular
 * videos: the channel's CURRENT live broadcast video id is resolved at
 * runtime from the official YouTube channel ([channelId]) — see
 * `LiveStreamResolver` — and then embedded. No HLS/CDN, no external player.
 *
 * @property id           Stable config key, e.g. "makkah" / "madinah".
 * @property title        Short label, e.g. "🕋 Makkah Live".
 * @property subtitle     Longer description, e.g. "Masjid al-Haram · Live from Makkah".
 * @property channelId    Official YouTube channel id broadcasting the stream.
 */
data class LiveStream(
    val id: String,
    val title: String,
    val subtitle: String,
    val channelId: String
)
