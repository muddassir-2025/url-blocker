package com.example.url_blocker.quran.data

import android.content.Context
import com.example.url_blocker.quran.model.QuranVerse
import java.io.File

/**
 * Local persistence for the Quran reminder.
 *
 * Two stores:
 *  - A raw JSON cache file (internal storage) holding the full downloaded
 *    translation, so everything works fully offline after the first download.
 *  - SharedPreferences holding the currently displayed verse, so widget reads
 *    and detail-screen reads are instant (no file parsing on the UI thread).
 */
class QuranStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
    private val arabicCacheFile = File(context.filesDir, AR_CACHE_FILE_NAME)

    /** True when the full translation has been downloaded and cached. */
    fun isCached(): Boolean = cacheFile.exists() && cacheFile.length() > 0L

    /** True when the Arabic (Uthmani) edition is downloaded and cached. */
    fun isArabicCached(): Boolean = arabicCacheFile.exists() && arabicCacheFile.length() > 0L

    /** Writes the raw downloaded JSON to the cache file. */
    fun saveJson(json: String) {
        cacheFile.writeText(json, Charsets.UTF_8)
    }

    /** Writes the raw downloaded Arabic JSON to its cache file. */
    fun saveArabicJson(json: String) {
        arabicCacheFile.writeText(json, Charsets.UTF_8)
    }

    /**
     * Reads + parses the cached Arabic edition into a (surah, ayah) → text
     * lookup; null when not cached or corrupt. Parsing is IO-heavy, so call
     * from a background thread.
     */
    fun loadArabicTexts(): Map<Pair<Int, Int>, String>? {
        if (!isArabicCached()) return null
        return try {
            QuranJsonParser.parseArabicTexts(arabicCacheFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A stamp identifying the CURRENT Arabic cache file contents (size and
     * mtime combined). Callers use it to know when a previously parsed map is
     * stale (e.g. the file was (re)downloaded) without re-reading the file.
     */
    fun arabicCacheStamp(): Long {
        return try {
            arabicCacheFile.length() * 100_003L + arabicCacheFile.lastModified()
        } catch (e: Exception) {
            -1L
        }
    }

    /** Reads + parses the cached translation into verses; null when not cached/corrupt. */
    fun loadVerses(): List<QuranVerse>? {
        if (!isCached()) return null
        return try {
            QuranJsonParser.parse(cacheFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The interval (in hours) between automatic new-verse refreshes.
     * Defaults to 6; the user can pick 1, 2, 3… hours from the verse screen.
     */
    fun getRefreshIntervalHours(): Int =
        prefs.getInt(KEY_REFRESH_INTERVAL_HOURS, DEFAULT_REFRESH_INTERVAL_HOURS)

    /** Persists the user-chosen interval (in hours) between new-verse refreshes. */
    fun setRefreshIntervalHours(hours: Int) {
        prefs.edit().putInt(KEY_REFRESH_INTERVAL_HOURS, hours).apply()
    }

    /** Persists the verse currently shown on the widget. */
    fun saveCurrentVerse(verse: QuranVerse) {
        prefs.edit()
            .putInt(KEY_SURAH_NUMBER, verse.surahNumber)
            .putInt(KEY_AYAH_NUMBER, verse.ayahNumber)
            .putString(KEY_SURAH_NAME, verse.surahName)
            .putString(KEY_SURAH_TRANSLATION, verse.surahTranslation)
            .putString(KEY_VERSE_TEXT, verse.text)
            .putString(KEY_ARABIC_TEXT, verse.arabicText)
            .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            .apply()
    }

    /** The currently displayed verse, or null before the first download. */
    fun readCurrentVerse(): QuranVerse? {
        val text = prefs.getString(KEY_VERSE_TEXT, null) ?: return null
        val surahNumber = prefs.getInt(KEY_SURAH_NUMBER, 0)
        val ayahNumber = prefs.getInt(KEY_AYAH_NUMBER, 0)
        if (surahNumber <= 0 || ayahNumber <= 0) return null
        return QuranVerse(
            surahNumber = surahNumber,
            ayahNumber = ayahNumber,
            surahName = prefs.getString(KEY_SURAH_NAME, "") ?: "",
            surahTranslation = prefs.getString(KEY_SURAH_TRANSLATION, "") ?: "",
            text = text,
            arabicText = prefs.getString(KEY_ARABIC_TEXT, "") ?: ""
        )
    }

    // ── Bookmarks ────────────────────────────────────────────────────

    /** Bookmarks are stored as "surahNumber:ayahNumber" references. */
    fun getBookmarks(): Set<String> =
        prefs.getStringSet(KEY_BOOKMARKS, emptySet()) ?: emptySet()

    fun isBookmarked(surahNumber: Int, ayahNumber: Int): Boolean =
        "$surahNumber:$ayahNumber" in getBookmarks()

    /** Toggles a bookmark. Returns true when it is now bookmarked. */
    fun toggleBookmark(surahNumber: Int, ayahNumber: Int): Boolean {
        val key = "$surahNumber:$ayahNumber"
        val current = getBookmarks().toMutableSet()
        val added = if (key in current) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        prefs.edit().putStringSet(KEY_BOOKMARKS, current).apply()
        return added
    }

    private companion object {
        const val PREFS_NAME = "quran_reminder_prefs"
        const val CACHE_FILE_NAME = "quran_en_sahih.json"
        const val AR_CACHE_FILE_NAME = "quran_ar_uthmani.json"

        const val KEY_SURAH_NUMBER = "current_surah_number"
        const val KEY_AYAH_NUMBER = "current_ayah_number"
        const val KEY_SURAH_NAME = "current_surah_name"
        const val KEY_SURAH_TRANSLATION = "current_surah_translation"
        const val KEY_VERSE_TEXT = "current_verse_text"
        const val KEY_ARABIC_TEXT = "current_verse_arabic_text"
        const val KEY_LAST_UPDATED = "current_verse_updated_at"
        const val KEY_REFRESH_INTERVAL_HOURS = "refresh_interval_hours"
        const val KEY_BOOKMARKS = "bookmarked_verses"
        const val DEFAULT_REFRESH_INTERVAL_HOURS = 6
    }
}
