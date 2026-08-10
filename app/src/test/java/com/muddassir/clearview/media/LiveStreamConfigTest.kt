package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.LiveStreamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Config sanity: locks the Live tab's official channel sources so an edit that
 * silently drops a channel or changes its identity is caught immediately.
 * Only OFFICIAL Saudi channels live here — the current live video id is
 * discovered at runtime and never hardcoded (an app update is never needed
 * because a new broadcast started).
 */
class LiveStreamConfigTest {

    @Test
    fun `streams contains exactly makkah and madinah with the official channel ids`() {
        val streams = LiveStreamConfig.streams
        assertEquals(listOf("makkah", "madinah"), streams.map { it.id })
        assertEquals(
            listOf("UCos52azQNBgW63_9uDJoPDA", "UCROKYPep-UuODNwyipe6JMw"),
            streams.map { it.channelId }
        )
        assertEquals(
            listOf("Saudi Qur'an TV", "Saudi Sunnah TV"),
            streams.map { it.name }
        )
    }

    @Test
    fun `every source has a channel url and channel page identity`() {
        LiveStreamConfig.streams.forEach { s ->
            assertTrue("${s.id} channelUrl must contain the channel id", s.channelUrl.contains(s.channelId))
        }
    }
}
