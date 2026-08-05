package com.muddassir.clearview.quran.data

import android.content.Context
import com.muddassir.clearview.quran.model.QuranVerse
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

    init {
        // One-time migration: editions are "The Clear Quran" (English) + the
        // IndoPak script. Older builds cached other editions (Sahih/Uthmani
        // from AlQuran.Cloud, and a broken khattab/indopak pair whose URLs
        // silently fell back to Arabic). When any of those old files existed we
        // remove them so they don't linger on disk — and clear the persisted
        // current verse (which may hold old/wrong text) so the next worker run
        // picks a fresh verse in the new edition.
        val hadOldCache = OLD_CACHE_FILE_NAMES.any { name ->
            File(context.filesDir, name).delete()
        }
        if (hadOldCache) {
            prefs.edit()
                .remove(KEY_SURAH_NUMBER)
                .remove(KEY_AYAH_NUMBER)
                .remove(KEY_SURAH_NAME)
                .remove(KEY_SURAH_TRANSLATION)
                .remove(KEY_VERSE_TEXT)
                .remove(KEY_ARABIC_TEXT)
                .apply()
        }
    }

    /** True when the full translation has been downloaded and cached. */
    fun isCached(): Boolean = cacheFile.exists() && cacheFile.length() > 0L

    /** True when the Arabic (IndoPak) edition is downloaded and cached. */
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
     * A stamp identifying the CURRENT English cache file contents (size and
     * mtime combined). Callers use it to know when a previously parsed list is
     * stale (e.g. the file was (re)downloaded) without re-reading the file.
     */
    fun cacheStamp(): Long {
        return try {
            cacheFile.length() * 100_003L + cacheFile.lastModified()
        } catch (e: Exception) {
            -1L
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

    /** Whether the app posts an OS notification when a new verse is chosen. */
    fun getQuranNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_QURAN_NOTIFICATIONS_ENABLED, DEFAULT_QURAN_NOTIFICATIONS_ENABLED)

    /** Persists the Quran-verse notification toggle. */
    fun setQuranNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QURAN_NOTIFICATIONS_ENABLED, enabled).apply()
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

    /** Removes the bookmark for a verse (no-op when not bookmarked). */
    fun removeBookmark(surahNumber: Int, ayahNumber: Int) {
        val key = "$surahNumber:$ayahNumber"
        val current = getBookmarks().toMutableSet()
        if (current.remove(key)) {
            prefs.edit().putStringSet(KEY_BOOKMARKS, current).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "quran_reminder_prefs"
        // The Clear Quran (Mustafa Khattab) English translation.
        const val CACHE_FILE_NAME = "quran_en_clear.json"
        // IndoPak Arabic script (v2 suffix: a previous build cached Arabic
        // under the plain "quran_ar_indopak.json" name, so the versioned name
        // forces a fresh download of the correct script).
        const val AR_CACHE_FILE_NAME = "quran_ar_indopak_v2.json"
        // Cache file names from older builds (deleted on first run of the new
        // build so stale/wrong content can't be mistaken for the current
        // edition).
        val OLD_CACHE_FILE_NAMES = listOf(
            "quran_en_sahih.json",
            "quran_ar_uthmani.json",
            "quran_en_khattab.json",
            "quran_ar_indopak.json"
        )

        const val KEY_SURAH_NUMBER = "current_surah_number"
        const val KEY_AYAH_NUMBER = "current_ayah_number"
        const val KEY_SURAH_NAME = "current_surah_name"
        const val KEY_SURAH_TRANSLATION = "current_surah_translation"
        const val KEY_VERSE_TEXT = "current_verse_text"
        const val KEY_ARABIC_TEXT = "current_verse_arabic_text"
        const val KEY_LAST_UPDATED = "current_verse_updated_at"
        const val KEY_REFRESH_INTERVAL_HOURS = "refresh_interval_hours"
        const val KEY_QURAN_NOTIFICATIONS_ENABLED = "quran_notifications_enabled"
        const val KEY_BOOKMARKS = "bookmarked_verses"
        const val DEFAULT_REFRESH_INTERVAL_HOURS = 6
        const val DEFAULT_QURAN_NOTIFICATIONS_ENABLED = true
    }
}
