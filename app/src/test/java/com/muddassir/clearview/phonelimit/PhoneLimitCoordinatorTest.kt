package com.muddassir.clearview.phonelimit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the pure Phone Limit logic — the user-input duration parser
 * ([PhoneLimitCoordinator.parseDuration]) and the countdown formatter
 * ([PhoneLimitCoordinator.format]). The stateful parts (store, service,
 * alarm, lock) are Android-bound and covered by manual/device testing.
 */
class PhoneLimitCoordinatorTest {

    // ── parseDuration ──────────────────────────────────────────────

    @Test
    fun `single unit forms`() {
        assertEquals(3_600_000L, PhoneLimitCoordinator.parseDuration("1h"))
        assertEquals(1_200_000L, PhoneLimitCoordinator.parseDuration("20m"))
        assertEquals(30_000L, PhoneLimitCoordinator.parseDuration("30s"))
        assertEquals(7_200_000L, PhoneLimitCoordinator.parseDuration("2h"))
        assertEquals(90_000L, PhoneLimitCoordinator.parseDuration("90s"))
    }

    @Test
    fun `combined unit forms`() {
        assertEquals(4_810_000L, PhoneLimitCoordinator.parseDuration("1h:20m:10s"))
        assertEquals(4_810_000L, PhoneLimitCoordinator.parseDuration("1h 20m 10s"))
        assertEquals(4_800_000L, PhoneLimitCoordinator.parseDuration("1h:20m"))
        assertEquals(2_730_000L, PhoneLimitCoordinator.parseDuration("45m 30s"))
        // Trailing junk that isn't a duration token is ignored.
        assertEquals(4_810_000L, PhoneLimitCoordinator.parseDuration("1h:20m:10s please"))
    }

    @Test
    fun `plain colon forms`() {
        assertEquals(4_810_000L, PhoneLimitCoordinator.parseDuration("1:20:10"))
        assertEquals(2_730_000L, PhoneLimitCoordinator.parseDuration("45:30"))
    }

    @Test
    fun `bare number means minutes`() {
        assertEquals(1_800_000L, PhoneLimitCoordinator.parseDuration("30"))
    }

    @Test
    fun `case and whitespace are tolerated`() {
        assertEquals(4_810_000L, PhoneLimitCoordinator.parseDuration("  1H:20M:10S  "))
    }

    @Test
    fun `invalid or zero input is rejected`() {
        assertNull(PhoneLimitCoordinator.parseDuration(""))
        assertNull(PhoneLimitCoordinator.parseDuration("abc"))
        assertNull(PhoneLimitCoordinator.parseDuration("0"))
        assertNull(PhoneLimitCoordinator.parseDuration("0h 0m 0s"))
        assertNull(PhoneLimitCoordinator.parseDuration("::"))
    }

    // ── format ─────────────────────────────────────────────────────

    @Test
    fun `format counts down as H MM SS`() {
        assertEquals("1:20:10", PhoneLimitCoordinator.format(4_810_000L))
        assertEquals("45:30", PhoneLimitCoordinator.format(2_730_000L))
        assertEquals("01:30", PhoneLimitCoordinator.format(90_000L))
        assertEquals("00:00", PhoneLimitCoordinator.format(0L))
        assertEquals("00:00", PhoneLimitCoordinator.format(-5L))
        // Sub-second remainder is truncated, never rounded up.
        assertEquals("00:01", PhoneLimitCoordinator.format(1_999L))
    }
}
