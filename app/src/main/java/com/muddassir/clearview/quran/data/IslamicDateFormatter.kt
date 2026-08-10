package com.muddassir.clearview.quran.data

import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

/**
 * Formats today's date as the Saudi Arabia / Umm al-Qura Islamic (Hijri) date
 * in English — e.g. "26 Safar 1448 AH" — using java.time's
 * [HijrahChronology] (the authoritative Umm al-Qura implementation, available
 * on API 24+ via core library desugaring).
 *
 * The displayed date is today's Hijri date shifted by the user's manual
 * adjustment ([adjustmentDays], ±1 day); 0 = the default calculated date.
 * The date auto-advances every day simply because it is computed from
 * today's Gregorian date — nothing needs to be stored per-day.
 *
 * Pure and unit-tested: no Android dependencies.
 */
object IslamicDateFormatter {

    /** English month names as used in Saudi Arabia (Umm al-Qura). */
    val MONTH_NAMES = listOf(
        "Muharram", "Safar", "Rabi al-Awwal", "Rabi al-Thani",
        "Jumada al-Awwal", "Jumada al-Thani", "Rajab", "Sha'ban",
        "Ramadan", "Shawwal", "Dhu al-Qadah", "Dhu al-Hijjah"
    )

    /**
     * The Umm al-Qura (Hijri) date for [gregorian], shifted by
     * [adjustmentDays] (−1 / 0 / +1). A +1 adjustment shows tomorrow's Hijri
     * date; −1 shows yesterday's.
     */
    fun hijriDateFor(gregorian: LocalDate, adjustmentDays: Int): HijrahDate {
        val base = HijrahDate.from(gregorian)
        return if (adjustmentDays == 0) base
        else base.plus(adjustmentDays.toLong(), ChronoUnit.DAYS)
    }

    /** Formats as "26 Safar 1448 AH". */
    fun format(gregorian: LocalDate, adjustmentDays: Int): String {
        val h = hijriDateFor(gregorian, adjustmentDays)
        // HijrahDate is a ChronoLocalDate, so its day/month/year are read via
        // ChronoField (no direct getters on the type).
        val day = h.get(ChronoField.DAY_OF_MONTH)
        val month = MONTH_NAMES[h.get(ChronoField.MONTH_OF_YEAR) - 1]
        val year = h.get(ChronoField.YEAR)
        return "$day $month $year AH"
    }
}
