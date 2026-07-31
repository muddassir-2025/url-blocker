package com.example.url_blocker.quran.data

import android.content.Context
import com.example.url_blocker.quran.model.QuranVerse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point for Quran verse data.
 *
 * Responsibilities:
 *  - Download the full translation once and cache it locally.
 *  - Select a random verse from the cache and persist it as the current verse.
 *  - Read the current verse instantly for widget/detail-screen rendering.
 *
 * All file/network IO happens on [Dispatchers.IO]; only [getCurrentVerse]
 * (SharedPreferences read) is safe to call from the main thread.
 */
class QuranRepository(context: Context) {

    private val store = QuranStore(context.applicationContext)

    /** True when the full translation is already cached locally. */
    suspend fun isCached(): Boolean = withContext(Dispatchers.IO) { store.isCached() }

    /**
     * Downloads + caches the full translation. No-op when a VALID cache exists
     * (valid = the cached JSON actually parses, so a truncated/corrupt file is
     * re-downloaded instead of blocking the widget on a forever-loading state).
     * @return true when a usable cache is present afterwards.
     */
    suspend fun downloadIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val cached = store.loadVerses()
        if (cached != null && cached.isNotEmpty()) {
            true
        } else {
            val json = QuranApi.download()
            if (json != null) {
                store.saveJson(json)
                true
            } else {
                false
            }
        }
    }

    /**
     * Picks a random verse from the cached translation, persists it as the
     * current verse and returns it. Avoids repeating the verse that is
     * currently displayed (when there is more than one choice). Returns null
     * only when no data is available (offline first run).
     */
    suspend fun pickRandomVerse(): QuranVerse? = withContext(Dispatchers.IO) {
        val verses = store.loadVerses() ?: return@withContext null
        if (verses.isEmpty()) return@withContext null

        val current = store.readCurrentVerse()
        var verse = verses.random()
        var attempts = 0
        // Don't show the exact same verse twice in a row when there is choice.
        while (verse == current && attempts < 5 && verses.size > 1) {
            verse = verses.random()
            attempts++
        }

        store.saveCurrentVerse(verse)
        verse
    }

    /** The currently displayed verse (instant; null before first download). */
    fun getCurrentVerse(): QuranVerse? = store.readCurrentVerse()
}
