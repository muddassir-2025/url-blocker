package com.example.url_blocker.media.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun formatsPlainCounts() {
        assertEquals("0", formatViews(0))
        assertEquals("123", formatViews(123))
        assertEquals("999", formatViews(999))
    }

    @Test
    fun formatsThousands() {
        assertEquals("1K", formatViews(1000))
        assertEquals("1.2K", formatViews(1234))
        assertEquals("12K", formatViews(12345))
        assertEquals("100K", formatViews(100000))
    }

    @Test
    fun formatsMillions() {
        assertEquals("1.2M", formatViews(1200000))
        assertEquals("1.5M", formatViews(1500000))
        assertEquals("2M", formatViews(1999999))
        assertEquals("12M", formatViews(12345678))
        assertEquals("100M", formatViews(100_000_000L))
    }

    @Test
    fun formatsBillions() {
        assertEquals("1B", formatViews(1_000_000_000L))
        assertEquals("1.5B", formatViews(1_500_000_000L))
    }
}
