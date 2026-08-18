package com.muddassir.clearview.quran.data

import android.content.Context

/**
 * Persists the user's manual Islamic-date adjustment (±1 day) in
 * SharedPreferences (the same pattern as [QuranStore]), so it survives app
 * restarts. The adjustment shifts the displayed Umm al-Qura date; 0 = the
 * default calculated date. The date itself is never stored — it is always
 * derived from today's Gregorian date at render time, so it auto-advances
 * every day while the user's adjustment is preserved.
 */
class IslamicDateStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored adjustment: −1, 0 or +1 day (defaults to 0). */
    fun adjustmentDays(): Int = prefs.getInt(KEY_ADJUSTMENT, 0).coerceIn(-1, 1)

    /** Persists [days] (−1, 0, +1); out-of-range values are clamped. */
    fun setAdjustmentDays(days: Int) {
        prefs.edit().putInt(KEY_ADJUSTMENT, days.coerceIn(-1, 1)).apply()
    }

    private companion object {
        const val PREFS_NAME = "islamic_date_prefs"
        const val KEY_ADJUSTMENT = "adjustment_days"
    }
}
