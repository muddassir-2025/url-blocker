package com.muddassir.clearview.media.data

import com.muddassir.clearview.media.model.LiveStreamSource

/**
 * Centralized configuration for the Live tab sources (Makkah / Madinah).
 *
 * Both are the OFFICIAL YouTube channels of the Saudi Broadcasting Authority —
 * the ONLY sources ever used:
 *
 *  - Makkah  → Saudi Qur'an TV (قناة القرآن الكريم) — live from Masjid al-Haram.
 *  - Madinah → Saudi Sunnah TV (قناة السنة النبوية) — live from Al-Masjid an-Nabawi.
 *
 * Only the channel identity lives here. The current live broadcast video id is
 * discovered at runtime from each channel (see [LiveStreamResolver]) and
 * persisted per channel in [LiveStreamCacheStore] — it is never hardcoded, so
 * a new broadcast never requires an app update. To switch or add sources
 * later, only this file needs editing.
 */
object LiveStreamConfig {

    val streams: List<LiveStreamSource> = listOf(
        LiveStreamSource(
            id = "makkah",
            title = "🕋 Makkah Live",
            subtitle = "Masjid al-Haram · Live from Makkah",
            name = "Saudi Qur'an TV",
            channelId = "UCos52azQNBgW63_9uDJoPDA",
            channelUrl = "https://www.youtube.com/channel/UCos52azQNBgW63_9uDJoPDA"
        ),
        LiveStreamSource(
            id = "makkah_backup",
            title = "🕋 Makkah Live (Backup)",
            subtitle = "AlQuran4k القرآن الكريم · Backup",
            name = "AlQuran4k القرآن الكريم",
            channelId = "UCn4gM8oVdh2y2b4T8v3G5aQ_backup",
            channelUrl = "https://www.youtube.com/@AlQuran4k"
        ),
        LiveStreamSource(
            id = "madinah",
            title = "🕌 Madinah Live",
            subtitle = "Al-Masjid an-Nabawi · Live from Madinah",
            name = "Saudi Sunnah TV",
            channelId = "UCROKYPep-UuODNwyipe6JMw",
            channelUrl = "https://www.youtube.com/channel/UCROKYPep-UuODNwyipe6JMw"
        )
    )

    fun streamById(id: String): LiveStreamSource? = streams.find { it.id == id }
}
