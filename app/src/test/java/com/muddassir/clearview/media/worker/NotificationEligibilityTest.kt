package com.muddassir.clearview.media.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [isNotificationEligible] — the worker's rule for which
 * videos may generate a notification. Locks in the baseline semantics: an
 * already-notified video never re-notifies, and a video published before the
 * channel was subscribed never notifies (even when the add-time baseline
 * fetch failed), while legacy channels (addedAt == 0) admit everything.
 */
class NotificationEligibilityTest {

    private val notified = setOf("seen1", "seen2")

    @Test
    fun `already notified video is never eligible again`() {
        assertFalse(
            isNotificationEligible(
                videoId = "seen1",
                publishedAtEpochMillis = 5_000L,
                addedAtEpochMillis = 1_000L,
                alreadyNotified = notified
            )
        )
    }

    @Test
    fun `video published before subscription is not eligible`() {
        // The add-time baseline may have failed — the timestamp guard must
        // still keep the channel's pre-existing backlog silent.
        assertFalse(
            isNotificationEligible(
                videoId = "old1",
                publishedAtEpochMillis = 500L,
                addedAtEpochMillis = 1_000L,
                alreadyNotified = emptySet()
            )
        )
    }

    @Test
    fun `video published after subscription is eligible`() {
        assertTrue(
            isNotificationEligible(
                videoId = "new1",
                publishedAtEpochMillis = 2_000L,
                addedAtEpochMillis = 1_000L,
                alreadyNotified = emptySet()
            )
        )
    }

    @Test
    fun `legacy channel with zero addedAt admits everything`() {
        // Channels saved before the subscription-timestamp feature existed.
        assertTrue(
            isNotificationEligible("any", 0L, 0L, emptySet())
        )
        assertTrue(
            isNotificationEligible("any", 123L, 0L, emptySet())
        )
    }

    @Test
    fun `newly notified video is immediately excluded on the next cycle`() {
        // Simulates the same video appearing in a later polling cycle after it
        // was notified: the id is now in the set, so it can't re-notify.
        assertFalse(
            isNotificationEligible("new1", 2_000L, 1_000L, setOf("new1"))
        )
    }
}
