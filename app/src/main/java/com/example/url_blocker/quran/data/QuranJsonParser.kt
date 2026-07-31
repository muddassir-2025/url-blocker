package com.example.url_blocker.quran.data

import com.example.url_blocker.quran.model.QuranVerse
import org.json.JSONObject

/**
 * Parses the AlQuran.Cloud `/v1/quran/en.sahih` response body into a flat list
 * of [QuranVerse]. Pure function (no Android dependencies) so it is unit-testable
 * on the JVM with the real org.json artifact.
 *
 * Expected shape:
 * ```
 * { "data": { "surahs": [ { "number": 1, "englishName": "Al-Faatiha",
 *     "englishNameTranslation": "The Opening", "ayahs": [
 *       { "numberInSurah": 1, "text": "In the name of Allah..." } ] } ] } }
 * ```
 */
object QuranJsonParser {

    /**
     * @throws org.json.JSONException if the payload is malformed (callers treat
     *         that as a failed download and retry later).
     */
    fun parse(raw: String): List<QuranVerse> {
        val root = JSONObject(raw)
        val data = root.getJSONObject("data")
        val surahs = data.getJSONArray("surahs")

        val verses = ArrayList<QuranVerse>(6236)
        for (i in 0 until surahs.length()) {
            val surah = surahs.getJSONObject(i)
            val surahNumber = surah.getInt("number")
            val surahName = surah.getString("englishName")
            val surahTranslation = surah.optString("englishNameTranslation", "")
            val ayahs = surah.getJSONArray("ayahs")
            for (j in 0 until ayahs.length()) {
                val ayah = ayahs.getJSONObject(j)
                verses.add(
                    QuranVerse(
                        surahNumber = surahNumber,
                        ayahNumber = ayah.getInt("numberInSurah"),
                        surahName = surahName,
                        surahTranslation = surahTranslation,
                        text = ayah.getString("text")
                    )
                )
            }
        }
        return verses
    }
}
