package com.example.url_blocker.media.data

import com.example.url_blocker.media.model.LiveStream

/**
 * Centralized configuration for the Live tab streams (Makkah / Madinah).
 *
 * Both are the OFFICIAL YouTube channels of the Saudi Broadcasting Authority:
 *
 *  - Makkah  → قناة القرآن الكريم (Qur'an TV) — live from Masjid al-Haram.
 *  - Madinah → قناة السنة النبوية (Sunnah TV) — live from Al-Masjid an-Nabawi.
 *
 * The current live broadcast video id is resolved at runtime from each
 * channel's `/live` page (see [LiveStreamResolver]) and played with the same
 * in-app IFrame player as regular videos — YouTube's channel-based live embed
 * (`/embed/live_stream?channel=…`, error 153) is blocked by YouTube, and the
 * Saudi CDN HLS mirrors (itworkscdn) return HTML block pages in practice, so
 * neither is used here.
 *
 * To switch or add streams later, only this file needs editing.
 */
object LiveStreamConfig {

    val streams: List<LiveStream> = listOf(
        LiveStream(
            id = "makkah",
            title = "🕋 Makkah Live",
            subtitle = "Masjid al-Haram · Live from Makkah",
            channelId = "UCos52azQNBgW63_9uDJoPDA" // قناة القرآن الكريم (SBA)
        ),
        LiveStream(
            id = "madinah",
            title = "🕌 Madinah Live",
            subtitle = "Al-Masjid an-Nabawi · Live from Madinah",
            channelId = "UCROKYPep-UuODNwyipe6JMw" // قناة السنة النبوية (SBA)
        )
    )

    fun streamById(id: String): LiveStream? = streams.find { it.id == id }
}
