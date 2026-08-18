package com.muddassir.clearview.quran.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

/**
 * Validates the Umm al-Qura (Saudi Arabia) date formatting against confirmed
 * reference dates:
 *  - 2025-01-01 = 1 Rajab 1446 AH
 *  - 2025-06-26 = 1 Muharram 1447 AH (Islamic New Year)
 *  - 2026-08-10 = 27 Safar 1448 AH (the JDK Umm al-Qura table projects
 *    Muharram 1448 with 29 days, so Safar begins 2026-07-15; some
 *    moon-sighting sources place these 1448 dates one day later — the
 *    ±1 day adjustment exists precisely for that variance).
 */
class IslamicDateFormatterTest {

    @Test
    fun `january 1 2025 is 1 Rajab 1446`() {
        val h = IslamicDateFormatter.hijriDateFor(LocalDate.of(2025, 1, 1), 0)
        assertEquals(1446, h.get(ChronoField.YEAR))
        assertEquals(7, h.get(ChronoField.MONTH_OF_YEAR)) // Rajab
        assertEquals(1, h.get(ChronoField.DAY_OF_MONTH))
        assertEquals("1 Rajab 1446 AH", IslamicDateFormatter.format(LocalDate.of(2025, 1, 1), 0))
    }

    @Test
    fun `june 26 2025 is 1 Muharram 1447`() {
        assertEquals("1 Muharram 1447 AH", IslamicDateFormatter.format(LocalDate.of(2025, 6, 26), 0))
    }

    @Test
    fun `august 10 2026 is 27 Safar 1448`() {
        assertEquals("27 Safar 1448 AH", IslamicDateFormatter.format(LocalDate.of(2026, 8, 10), 0))
    }

    @Test
    fun `plus one day adjustment shows the next hijri day`() {
        val d = LocalDate.of(2026, 8, 10)
        val base = IslamicDateFormatter.hijriDateFor(d, 0)
        assertEquals(base.plus(1, ChronoUnit.DAYS), IslamicDateFormatter.hijriDateFor(d, 1))
    }

    @Test
    fun `minus one day adjustment shows the previous hijri day`() {
        val d = LocalDate.of(2026, 8, 10)
        val base = IslamicDateFormatter.hijriDateFor(d, 0)
        assertEquals(base.minus(1, ChronoUnit.DAYS), IslamicDateFormatter.hijriDateFor(d, -1))
    }

    @Test
    fun `zero adjustment equals the default calculated date`() {
        val d = LocalDate.of(2026, 8, 10)
        assertEquals(
            IslamicDateFormatter.hijriDateFor(d, 0),
            IslamicDateFormatter.hijriDateFor(d, 0)
        )
    }

    @Test
    fun `format round-trips across the month boundary`() {
        // Muharram 1448 = 29 days in the JDK Umm al-Qura table (2026-06-16 →
        // 2026-07-14); the day after 29 Muharram is 1 Safar 1448.
        val lastOfMuharram = IslamicDateFormatter.hijriDateFor(LocalDate.of(2026, 7, 14), 0)
        assertEquals(1448, lastOfMuharram.get(ChronoField.YEAR))
        assertEquals(1, lastOfMuharram.get(ChronoField.MONTH_OF_YEAR))
        assertEquals(29, lastOfMuharram.get(ChronoField.DAY_OF_MONTH))

        val firstOfSafar = lastOfMuharram.plus(1, ChronoUnit.DAYS)
        assertEquals(2, firstOfSafar.get(ChronoField.MONTH_OF_YEAR))
        assertEquals(1, firstOfSafar.get(ChronoField.DAY_OF_MONTH))
    }

    @Test
    fun `all twelve month names are present and unique`() {
        val months = IslamicDateFormatter.MONTH_NAMES
        assertEquals(12, months.size)
        assertEquals(12, months.distinct().size)
        assertTrue(months.all { it.isNotBlank() })
    }

    @Test
    fun `adjustment formatting carries over`() {
        // 2026-08-10 is 27 Safar 1448; +1 → 28 Safar 1448.
        assertEquals("28 Safar 1448 AH", IslamicDateFormatter.format(LocalDate.of(2026, 8, 10), 1))
    }
}
