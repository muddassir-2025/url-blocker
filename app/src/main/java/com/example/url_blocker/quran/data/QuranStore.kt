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

    /** True when the full translation has been downloaded and cached. */
    fun isCached(): Boolean = cacheFile.exists() && cacheFile.length() > 0L

    /** Writes the raw downloaded JSON to the cache file. */
    fun saveJson(json: String) {
        cacheFile.writeText(json, Charsets.UTF_8)
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

    /** Persists the verse currently shown on the widget. */
    fun saveCurrentVerse(verse: QuranVerse) {
        prefs.edit()
            .putInt(KEY_SURAH_NUMBER, verse.surahNumber)
            .putInt(KEY_AYAH_NUMBER, verse.ayahNumber)
            .putString(KEY_SURAH_NAME, verse.surahName)
            .putString(KEY_SURAH_TRANSLATION, verse.surahTranslation)
            .putString(KEY_VERSE_TEXT, verse.text)
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
            text = text
        )
    }

    private companion object {
        const val PREFS_NAME = "quran_reminder_prefs"
        const val CACHE_FILE_NAME = "quran_en_sahih.json"

        const val KEY_SURAH_NUMBER = "current_surah_number"
        const val KEY_AYAH_NUMBER = "current_ayah_number"
        const val KEY_SURAH_NAME = "current_surah_name"
        const val KEY_SURAH_TRANSLATION = "current_surah_translation"
        const val KEY_VERSE_TEXT = "current_verse_text"
        const val KEY_LAST_UPDATED = "current_verse_updated_at"
    }
}
