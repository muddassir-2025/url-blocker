package com.muddassir.clearview.quran.model

/**
 * A single Quran verse from the Sahih International English translation.
 *
 * @property surahNumber  1-based surah number (1..114)
 * @property ayahNumber   Verse number within its surah (1-based)
 * @property surahName    English transliterated surah name, e.g. "At-Talaaq"
 * @property surahTranslation English meaning of the surah name, e.g. "Divorce"
 * @property text         English verse text (Sahih International)
 * @property arabicText   Arabic verse text (Uthmani script, quran-uthmani).
 *                        Empty string when the Arabic edition is not cached
 *                        yet (the widget and detail screen still work — they
 *                        just show English).
 */
data class QuranVerse(
    val surahNumber: Int,
    val ayahNumber: Int,
    val surahName: String,
    val surahTranslation: String,
    val text: String,
    val arabicText: String = ""
)
