package com.muddassir.clearview.quran.data

import android.content.Context
import com.muddassir.clearview.quran.model.DhikrItem

/**
 * Local persistence for the Dhikr Counter. Everything lives in one
 * SharedPreferences file (the whole [DhikrItem] list as JSON — counts,
 * targets, visibility and order included — plus the currently selected dhikr
 * and the vibration preference), so the counter state survives app restarts
 * and every write is cheap and atomic. Reads are instant, so the counter UI
 * can save on every single tap without any jank.
 */
class DhikrStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Every dhikr with its counting state, in selection order. Returns the
     * built-in list on first launch (and when the stored JSON is missing or
     * corrupt — the stored selection is then stale, so it is dropped too).
     */
    fun getItems(): List<DhikrItem> {
        val stored = prefs.getString(KEY_ITEMS, null)
        if (stored == null) return DhikrCodec.defaults()
        val decoded = DhikrCodec.decode(stored)
        if (decoded.isEmpty()) {
            prefs.edit().remove(KEY_SELECTED_ID).apply()
            return DhikrCodec.defaults()
        }
        return DhikrCodec.ordered(decoded)
    }

    /** Persists the full dhikr list (counts included — called on every change). */
    fun saveItems(items: List<DhikrItem>) {
        prefs.edit().putString(KEY_ITEMS, DhikrCodec.encode(DhikrCodec.reindexed(items))).apply()
    }

    /** The id of the dhikr the user last counted, or null before the first pick. */
    fun getSelectedId(): String? =
        prefs.getString(KEY_SELECTED_ID, null)?.takeIf { it.isNotBlank() }

    /** Persists the selected dhikr id. */
    fun setSelectedId(id: String) {
        prefs.edit().putString(KEY_SELECTED_ID, id).apply()
    }

    /** Whether counting should vibrate on every tap (default ON). */
    fun getVibrationEnabled(): Boolean =
        prefs.getBoolean(KEY_VIBRATION, true)

    /** Persists the vibration preference. */
    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "dhikr_counter"
        const val KEY_ITEMS = "items"
        const val KEY_SELECTED_ID = "selected_id"
        const val KEY_VIBRATION = "vibration_enabled"
    }
}
