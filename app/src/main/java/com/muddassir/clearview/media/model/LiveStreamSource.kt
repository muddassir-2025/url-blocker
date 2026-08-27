package com.muddassir.clearview.media.model

/**
 * A permanent live-stream SOURCE for the Live tab (Makkah / Madinah).
 *
 * The OFFICIAL YouTube channel is the stable identity: the currently-airing
 * broadcast video id changes over time, but the channel id never does, so this
 * config stores ONLY channel identifiers. The current live video is discovered
 * at runtime from the channel (see `LiveStreamResolver`) and is NEVER hardcoded
 * here — an app update is never needed because a new broadcast started.
 *
 * @property id           Stable config key, e.g. "makkah" / "madinah".
 * @property title        Short UI label, e.g. "🕋 Makkah Live".
 * @property subtitle     Longer UI caption, e.g. "Masjid al-Haram · Live from Makkah".
 * @property name         Official channel name (Saudi Qur'an TV / Saudi Sunnah TV).
 * @property channelId    Official YouTube channel id — the stable identity.
 * @property channelUrl   Official channel page URL, used as the base for the
 *                        resolver's discovery endpoints.
 */
data class LiveStreamSource(
    val id: String,
    val title: String,
    val subtitle: String,
    val name: String,
    val channelId: String,
    val channelUrl: String,
    val fallbackChannelId: String? = null,
    val fallbackChannelUrl: String? = null
)
