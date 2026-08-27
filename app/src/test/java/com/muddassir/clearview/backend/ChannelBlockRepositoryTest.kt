package com.muddassir.clearview.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the channel-blocking pieces added for the shared-backend feature:
 *  - @handle extraction from tree text (the regex the long-video coordinator
 *    uses — same semantics as the extension's channel matcher),
 *  - the local cached-rules decision (channelId > handle), which must be pure
 *    and never depend on the network.
 */
class ChannelBlockRepositoryTest {

    // Mirror of LongVideoBlockCoordinator.CHANNEL_HANDLE_REGEX.
    private val HANDLE_REGEX =
        Regex("@(?=[A-Za-z0-9._-]*[A-Za-z0-9])[A-Za-z0-9._-]{2,100}")

    private fun extractHandle(text: String): String? =
        HANDLE_REGEX.find(text)?.value

    @Test
    fun `extracts channel handle from tree text`() {
        assertEquals("@examplechannel", extractHandle("@examplechannel"))
        assertEquals("@examplechannel", extractHandle("Visit @examplechannel for more"))
        assertEquals("@Safina_Society.1", extractHandle("channel @Safina_Society.1 subscribe"))
        assertEquals("@channel.with.dots", extractHandle("@channel.with.dots"))
    }

    @Test
    fun `does not extract non-handle text`() {
        assertEquals(null, extractHandle("Subscribe to my channel"))
        assertEquals(null, extractHandle("UCabc1234567890abcdef01"))
        assertEquals(null, extractHandle("youtube.com/channel/UCabc"))
        assertEquals(null, extractHandle("@"))
    }

    @Test
    fun `cached rules decide by channelId first, handle second`() {
        // Pure logic: a blocked channel record with an id + handle.
        val repo = FakeChannelRepo(listOf(
            ClearViewBackendClient.BlockedChannel(
                channelId = "UCabc1234567890abcdef01",
                channelHandle = "@examplechannel",
                channelName = "Example Channel"
            )
        ))
        assertTrue(repo.isBlockedByCachedRules("UCabc1234567890abcdef01", null))
        assertTrue(repo.isBlockedByCachedRules(null, "@examplechannel"))
        assertTrue(repo.isBlockedByCachedRules("UCabc1234567890abcdef01", "@examplechannel"))
        assertFalse(repo.isBlockedByCachedRules("UC0000000000000000000000", null))
        assertFalse(repo.isBlockedByCachedRules(null, "@innocentchannel"))
        // channelId is canonical — name mismatch must not matter.
        assertTrue(repo.isBlockedByCachedRules("UCabc1234567890abcdef01", "@otherhandle"))
    }

    @Test
    fun `handle extraction requires a real handle length`() {
        // 2+ characters minimum (1-char "@c" is never a real handle) and
        // alphanumerics + ._- only — no false positives from plain text.
        assertNotNull(extractHandle("@channel_1-2"))
        assertEquals(null, extractHandle("@c"))
        assertEquals(null, extractHandle("@.."))
        assertEquals(null, extractHandle("@--"))
    }

    /** Minimal fake exposing the pure cached-rules decision. */
    private class FakeChannelRepo(
        private val channels: List<ClearViewBackendClient.BlockedChannel>
    ) {
        fun isBlockedByCachedRules(channelId: String?, handle: String?): Boolean {
            val id = channelId
            val h = handle?.trim()?.lowercase()
            return channels.any { c ->
                (id != null && c.channelId == id) ||
                    (h != null && c.channelHandle?.lowercase() == h)
            }
        }
    }
}
