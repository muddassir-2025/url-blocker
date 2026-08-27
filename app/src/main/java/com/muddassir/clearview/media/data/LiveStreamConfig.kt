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
            id = "madinah",
            title = "🕌 Madinah Live",
            subtitle = "Al-Masjid an-Nabawi · Live from Madinah",
            name = "Saudi Sunnah TV",
            channelId = "UCROKYPep-UuODNwyipe6JMw",
            channelUrl = "https://www.youtube.com/channel/UCROKYPep-UuODNwyipe6JMw"
        )
    )

    /** Fallback streams used when the primary channel broadcast is unavailable. */
    val backupStreams: Map<String, LiveStreamSource> = mapOf(
        "makkah" to LiveStreamSource(
            id = "makkah_backup",
            title = "🕋 Makkah Live (Backup)",
            subtitle = "AlQuran4k القرآن الكريم · Backup",
            name = "AlQuran4k القرآن الكريم",
            channelId = "UCfBw_uwZb_oFLyVsjWk6owQ",
            channelUrl = "https://www.youtube.com/channel/UCfBw_uwZb_oFLyVsjWk6owQ"
        )
    )

    fun streamById(id: String): LiveStreamSource? =
        streams.find { it.id == id } ?: backupStreams[id] ?: backupStreams.values.find { it.id == id }
}
