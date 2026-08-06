package com.muddassir.clearview.media.util

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

    @Test
    fun formatsBytesInBaseUnits() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("500 B", formatBytes(500))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun formatsBytesInKilobytes() {
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("14 KB", formatBytes(14336))
        assertEquals("1023.9 KB", formatBytes(1_048_473))
    }

    @Test
    fun formatsBytesInMegabytes() {
        assertEquals("1 MB", formatBytes(1024 * 1024))
        assertEquals("286.7 MB", formatBytes(300_647_000))
        // Decimals persist across the thousand boundary.
        assertEquals("1001.4 MB", formatBytes(1_050_000_000))
    }

    @Test
    fun formatsBytesInGigabytes() {
        assertEquals("1 GB", formatBytes(1L shl 30))
        assertEquals("1.5 GB", formatBytes(1_610_612_736))
        assertEquals("2 GB", formatBytes(2_147_483_648))
    }

    @Test
    fun formatsNegativeBytesAsZero() {
        assertEquals("0 B", formatBytes(-5))
    }

    @Test
    fun etaRemainingIsEmptyWhileUnknownOrTooEarly() {
        assertEquals("", formatEtaRemaining(-1))
        assertEquals("", formatEtaRemaining(0))
        assertEquals("", formatEtaRemaining(1))
    }

    @Test
    fun etaRemainingFormatsSeconds() {
        assertEquals("~30s left", formatEtaRemaining(30))
        assertEquals("~59s left", formatEtaRemaining(59))
    }

    @Test
    fun etaRemainingFormatsMinutesAndHours() {
        assertEquals("~2m 30s left", formatEtaRemaining(150))
        assertEquals("~1h 0m left", formatEtaRemaining(3600))
        assertEquals("~1h 5m left", formatEtaRemaining(3900))
    }
}
